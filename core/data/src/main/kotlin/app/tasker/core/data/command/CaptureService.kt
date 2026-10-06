package app.tasker.core.data.command

import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AppSettings
import app.tasker.core.model.Bucket
import app.tasker.core.model.CaptureChannel
import app.tasker.core.model.Deadline
import app.tasker.core.model.EnrichState
import app.tasker.core.model.Estimate
import app.tasker.core.model.FieldSource
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectId
import app.tasker.core.model.ProjectStatus
import app.tasker.core.model.Source
import app.tasker.core.model.SourceKind
import app.tasker.core.model.Task
import app.tasker.core.model.TaskField
import app.tasker.core.model.TaskStatus
import app.tasker.core.parser.ParseContext
import app.tasker.core.parser.ParseResult
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Values the user set through chips instead of typing them (CAP-4: tap on a chip opens a picker). */
data class CaptureOverrides(
    val deadline: FieldUpdate<Deadline?> = FieldUpdate.Keep,
    val planDate: FieldUpdate<LocalDate?> = FieldUpdate.Keep,
    val estimate: FieldUpdate<Estimate?> = FieldUpdate.Keep,
    val bucket: FieldUpdate<Bucket?> = FieldUpdate.Keep,
    val projectId: FieldUpdate<ProjectId?> = FieldUpdate.Keep,
)

/** Where a shared text came from ("Поделиться", CAP-6): the sending app and a link if there is one. */
data class SharedOrigin(val appPackage: String?, val url: String?)

data class CaptureRequest(
    val input: String,
    val channel: CaptureChannel,
    /** Fragments the user returned to plain text by removing their chips. */
    val literalRanges: List<IntRange> = emptyList(),
    val overrides: CaptureOverrides = CaptureOverrides(),
    /** Context of the capture point, e.g. "add step" inside a project; parsed values win over it. */
    val defaultProjectId: ProjectId? = null,
    val defaultBucket: Bucket? = null,
    val note: String? = null,
    val sharedFrom: SharedOrigin? = null,
)

data class CaptureOutcome(val task: Task, val createdProject: Project?, val parse: ParseResult)

/**
 * Capture (CAP-1…CAP-8): the text is parsed locally and the task is saved at once; chips show what was recognized.
 * Network never blocks input: with AI on, the task is only queued for enrichment (CAP-7).
 */
@Singleton
class CaptureService @Inject constructor(
    private val runner: TxRunner,
    private val clock: DayClock,
    private val settings: SettingsRepository,
    private val parsers: ParserProvider,
    db: TaskerDatabase,
) {
    private val projectDao = db.projectDao()

    /** Names of active projects for `#project` chips. */
    val projectNames: Flow<Set<String>> = projectDao.observeActive()
        .map { projects -> projects.mapTo(LinkedHashSet()) { it.name } }
        .distinctUntilChanged()

    /** Parses for chips while typing; [knownProjects] comes from [projectNames]. */
    fun parse(
        input: String,
        settings: AppSettings,
        knownProjects: Set<String>,
        literalRanges: List<IntRange> = emptyList(),
    ): ParseResult = parsers.parser(settings).parse(input, context(knownProjects, literalRanges))

    suspend fun parse(input: String, literalRanges: List<IntRange> = emptyList()): ParseResult =
        parse(input, settings.current(), projectDao.activeNames().toSet(), literalRanges)

    private fun context(knownProjects: Set<String>, literalRanges: List<IntRange>): ParseContext {
        val now = clock.now()
        return ParseContext(
            now = LocalDateTime.ofInstant(now, clock.zone()),
            today = clock.logicalDay(now),
            slashDateOrder = parsers.slashDateOrder(),
            literalRanges = literalRanges,
            knownProjects = knownProjects,
        )
    }

    suspend fun capture(request: CaptureRequest): TxResult<CaptureOutcome> {
        require(request.input.isNotBlank()) { "Nothing to capture" }
        val parse = parse(request.input, request.literalRanges)
        return runner.user {
            var createdProject: Project? = null
            val sources = HashMap<TaskField, FieldSource>()
            sources[TaskField.TITLE] = FieldSource.USER

            val projectId = when (val override = request.overrides.projectId) {
                is FieldUpdate.Set -> override.value?.also { sources[TaskField.PROJECT] = FieldSource.USER }
                FieldUpdate.Keep -> parse.project?.let { parsed ->
                    sources[TaskField.PROJECT] = FieldSource.PARSER
                    val existing = if (parsed.isNew) projectDao.byName(parsed.name) else projectDao.activeByName(parsed.name)
                    existing?.id ?: insertProject(newProject(parsed.name)).also { createdProject = it }.id
                } ?: request.defaultProjectId?.also { sources[TaskField.PROJECT] = FieldSource.USER }
            }
            projectId?.let { id -> reactivateIfArchived(id) }

            val bucket = pick(request.overrides.bucket, parse.bucket, request.defaultBucket, TaskField.BUCKET, sources)
            val planDate = pick(request.overrides.planDate, parse.planDate, null, TaskField.PLAN_DATE, sources)
            val estimate = pick(request.overrides.estimate, parse.estimate, null, TaskField.ESTIMATE, sources)
            val deadline = pick(
                request.overrides.deadline,
                parse.deadline?.let { Deadline(it.date, it.time, if (it.time != null) zone else null) },
                null,
                TaskField.DEADLINE,
                sources,
            )
            if (parse.tags.isNotEmpty()) sources[TaskField.TAGS] = FieldSource.PARSER
            if (!request.note.isNullOrBlank()) sources[TaskField.NOTE] = FieldSource.USER

            val done = parse.alreadyDone
            val aiActive = settings.ai.isActive
            val task = Task(
                id = newId(),
                title = parse.title,
                rawInput = request.input,
                note = request.note,
                status = if (done) TaskStatus.DONE else TaskStatus.OPEN,
                bucket = bucket,
                position = nextPosition(bucket),
                deadline = deadline,
                planDate = planDate,
                estimate = estimate,
                projectId = projectId,
                tags = parse.tags,
                lastTouchedAt = now,
                offPlan = done,
                captureChannel = request.channel,
                fieldSources = sources,
                enrichState = if (aiActive && estimate == null && !done) EnrichState.PENDING else EnrichState.NONE,
                createdAt = now,
                completedAt = if (done) now else null,
            )
            val stored = insertTask(task)
            linkSources(stored, parse, request.sharedFrom)
            CaptureOutcome(stored, createdProject, parse)
        }
    }

    private fun <T> pick(
        override: FieldUpdate<T?>,
        parsed: T?,
        default: T?,
        field: TaskField,
        sources: MutableMap<TaskField, FieldSource>,
    ): T? = when (override) {
        is FieldUpdate.Set -> override.value.also { if (it != null) sources[field] = FieldSource.USER }
        FieldUpdate.Keep -> when {
            parsed != null -> parsed.also { sources[field] = FieldSource.PARSER }
            default != null -> default.also { sources[field] = FieldSource.USER }
            else -> null
        }
    }

    private suspend fun Tx.linkSources(task: Task, parse: ParseResult, shared: SharedOrigin?) {
        val urls = LinkedHashSet<String>()
        parse.links.forEach { urls += it.url }
        shared?.url?.let { urls += it }
        for (url in urls) {
            addSource(Source(id = newId(), taskId = task.id, kind = SourceKind.LINK, url = url, appPackage = shared?.appPackage))
        }
        if (urls.isEmpty() && shared?.appPackage != null) {
            addSource(Source(id = newId(), taskId = task.id, kind = SourceKind.APP, appPackage = shared.appPackage))
        }
    }

    private suspend fun Tx.reactivateIfArchived(projectId: ProjectId) {
        val project = projectOrNull(projectId) ?: throw ProjectNotFoundException(projectId)
        if (project.status != ProjectStatus.ACTIVE) {
            updateProject(project.copy(status = ProjectStatus.ACTIVE, archivedAt = null, completedAt = null))
        }
    }

    private suspend fun Tx.newProject(name: String): Project = Project(
        id = newId(),
        name = name.trim(),
        position = nextProjectPosition(),
        lastActivityAt = now,
        createdAt = now,
    )
}
