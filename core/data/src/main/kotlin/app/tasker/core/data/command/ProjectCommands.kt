package app.tasker.core.data.command

import app.tasker.core.data.mapper.toModel
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.domain.order.Positions
import app.tasker.core.domain.plan.TaskTiming
import app.tasker.core.model.ArchiveReason
import app.tasker.core.model.EntityType
import app.tasker.core.model.EventType
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectId
import app.tasker.core.model.ProjectStatus
import app.tasker.core.model.Task
import app.tasker.core.model.TaskId
import app.tasker.core.model.TaskStatus
import javax.inject.Inject
import javax.inject.Singleton

/** What archiving a project will do; shown in the confirmation dialog (§8.5, interpretation 26). */
data class ProjectArchivePreview(val toArchive: List<Task>, val toDetach: List<Task>)

data class ProjectArchiveOutcome(val project: Project, val archived: List<TaskId>, val detached: List<TaskId>)

/** User commands on projects (EXE-4, EXE-5, §8.5). Projects are never archived automatically. */
@Singleton
class ProjectCommands @Inject constructor(
    private val runner: TxRunner,
    private val tasks: TaskCommands,
    private val capture: CaptureService,
    private val db: TaskerDatabase,
) {
    suspend fun create(name: String, outcome: String? = null): TxResult<Project> = runner.user {
        if (name.isBlank()) throw InvalidCommandException("A project needs a name")
        insertProject(
            Project(
                id = newId(),
                name = name.trim(),
                outcome = outcome?.trim()?.ifEmpty { null },
                position = nextProjectPosition(),
                lastActivityAt = now,
                createdAt = now,
            ),
        )
    }

    suspend fun rename(projectId: ProjectId, name: String): TxResult<Project> = runner.user {
        if (name.isBlank()) throw InvalidCommandException("A project needs a name")
        updateProject(project(projectId).copy(name = name.trim()))
    }

    suspend fun setOutcome(projectId: ProjectId, outcome: String?): TxResult<Project> = runner.user {
        updateProject(project(projectId).copy(outcome = outcome?.trim()?.ifEmpty { null }))
    }

    /** "Still relevant" from the review queue: the project leaves review and its activity restarts. */
    suspend fun confirmRelevant(projectId: ProjectId): TxResult<Project> = runner.user {
        updateProject(project(projectId).copy(inReview = false, reviewSince = null), EventType.REVIEW_DECIDED)
    }

    suspend fun complete(projectId: ProjectId): TxResult<Project> = runner.user {
        val before = project(projectId)
        updateProject(
            before.copy(status = ProjectStatus.COMPLETED, completedAt = now, inReview = false, reviewSince = null),
            EventType.STATUS_CHANGED,
        )
    }

    suspend fun reopen(projectId: ProjectId): TxResult<Project> = runner.user {
        updateProject(project(projectId).copy(status = ProjectStatus.ACTIVE, completedAt = null), EventType.STATUS_CHANGED)
    }

    suspend fun previewArchive(projectId: ProjectId): ProjectArchivePreview = runner.user {
        val (archive, detach) = splitForArchive(this, projectId)
        ProjectArchivePreview(archive, detach)
    }.value

    /**
     * Archives the project in one batch with its unfinished tasks, except tasks with a future deadline (TTL-7): they
     * stay open and leave the project.
     */
    suspend fun archive(projectId: ProjectId): TxResult<ProjectArchiveOutcome> = runner.user {
        val project = project(projectId)
        val (archive, detach) = splitForArchive(this, projectId)
        archive.forEach { tasks.archiveIn(this, it.id, ArchiveReason.PROJECT_ARCHIVED) }
        detach.forEach { updateTask(it.copy(projectId = null), EventType.MOVED) }
        val archived = updateProject(
            project.copy(status = ProjectStatus.ARCHIVED, archivedAt = now, inReview = false, reviewSince = null),
            EventType.ARCHIVED,
        )
        ProjectArchiveOutcome(archived, archive.map { it.id }, detach.map { it.id })
    }

    private suspend fun splitForArchive(tx: Tx, projectId: ProjectId): Pair<List<Task>, List<Task>> = with(tx) {
        project(projectId)
        val active = db.taskDao().projectTasks(projectId).filter { it.status.isActive }.mapNotNull { taskOrNull(it.id) }
        active.partition { !TaskTiming.hasFutureDeadline(it, now, today, zone) }
    }

    /** Restores an archived project; with [restoreTasks] also the tasks archived together with it. */
    suspend fun restore(projectId: ProjectId, restoreTasks: Boolean): TxResult<Project> {
        val archiveBatch = db.eventDao().latestOfType(projectId, EventType.ARCHIVED.name)?.batchId
        val batchTaskIds = archiveBatch?.let { batch ->
            db.eventDao().batch(batch).map { it.toModel() }
                .filter { it.entityType == EntityType.TASK && it.type == EventType.ARCHIVED }
                .map { it.entityId }
        }.orEmpty()
        return runner.user {
            val before = project(projectId)
            val restored = updateProject(
                before.copy(status = ProjectStatus.ACTIVE, archivedAt = null, completedAt = null),
                EventType.RESTORED,
            )
            if (restoreTasks) {
                for (taskId in batchTaskIds) {
                    val task = taskOrNull(taskId) ?: continue
                    if (task.status == TaskStatus.ARCHIVED && task.archiveReason == ArchiveReason.PROJECT_ARCHIVED) {
                        tasks.restoreIn(this, taskId)
                    }
                }
            }
            restored
        }
    }

    /** Manual order of projects. */
    suspend fun reorder(projectId: ProjectId, orderedIds: List<ProjectId>): TxResult<Project> = runner.user {
        val index = orderedIds.indexOf(projectId)
        if (index < 0) throw InvalidCommandException("The moved project is not in the list")
        val previous = orderedIds.getOrNull(index - 1)?.let { projectOrNull(it)?.position }
        val next = orderedIds.getOrNull(index + 1)?.let { projectOrNull(it)?.position }
        val position = Positions.between(previous, next) ?: run {
            Positions.rebalanced(orderedIds.size).zip(orderedIds).forEach { (position, id) ->
                val project = project(id)
                if (id != projectId) updateProject(project.copy(position = position), EventType.REORDERED, touch = false)
            }
            Positions.rebalanced(orderedIds.size)[index]
        }
        updateProject(project(projectId).copy(position = position), EventType.REORDERED)
    }

    /** "Add step" for a project without a next step (EXE-5): a regular capture inside the project. */
    suspend fun addStep(projectId: ProjectId, text: String) = capture.capture(
        CaptureRequest(input = text, channel = app.tasker.core.model.CaptureChannel.BAR, defaultProjectId = projectId),
    )
}
