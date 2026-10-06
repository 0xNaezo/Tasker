package app.tasker.core.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import app.tasker.core.data.mapper.toModel
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.domain.provenance.FieldProvenance
import app.tasker.core.domain.rules.Rules
import app.tasker.core.model.Bucket
import app.tasker.core.model.ContextSnapshot
import app.tasker.core.model.Event
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectId
import app.tasker.core.model.ProjectStatus
import app.tasker.core.model.Source
import app.tasker.core.model.Tag
import app.tasker.core.model.Task
import app.tasker.core.model.TaskField
import app.tasker.core.model.TaskId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Task card (§14.2): the latest snapshot first (EXC-4), AI-filled fields (AI-4), links and sources, history. */
data class TaskDetail(
    val task: Task,
    val project: Project?,
    val snapshots: List<ContextSnapshot>,
    val sources: List<Source>,
    val history: List<Event>,
) {
    val aiFields: Set<TaskField> get() = FieldProvenance.aiFields(task)
    val latestSnapshot: ContextSnapshot? get() = snapshots.firstOrNull()
}

/** A project row: counts and the derived "no next step" mark (EXE-5). */
data class ProjectSummary(
    val project: Project,
    val activeTasks: Int,
) {
    val noNextStep: Boolean get() = project.status == ProjectStatus.ACTIVE && activeTasks == 0
}

data class ProjectDetail(val project: Project, val tasks: List<Task>) {
    val noNextStep: Boolean get() = Rules.hasNoNextStep(project, tasks)
}

/** Read side of tasks and projects; screens never see DAOs (tech plan §6). */
@Singleton
class TaskRepository @Inject constructor(private val db: TaskerDatabase) {
    private val taskDao = db.taskDao()
    private val projectDao = db.projectDao()
    private val contentDao = db.contentDao()

    fun observeTask(id: TaskId): Flow<Task?> = taskDao.observe(id).map { it?.toModel() }.distinctUntilChanged()

    fun observeDetail(id: TaskId): Flow<TaskDetail?> = combine(
        observeTask(id),
        contentDao.observeSnapshots(id),
        contentDao.observeSources(id),
        db.eventDao().observeHistory(id),
    ) { task, snapshots, sources, events ->
        task?.let {
            TaskDetail(it, null, snapshots.map { s -> s.toModel() }, sources.map { s -> s.toModel() }, events.map { e -> e.toModel() })
        }
    }.combine(projectDao.observeAll()) { detail, projects ->
        detail?.copy(project = detail.task.projectId?.let { id -> projects.firstOrNull { it.id == id }?.toModel() })
    }

    suspend fun task(id: TaskId): Task? = taskDao.getWithTags(id)?.toModel()

    fun observeActive(): Flow<List<Task>> = taskDao.observeActive().map { list -> list.map { it.toModel() } }

    /** Inbox, newest first (CAP-5). */
    fun observeInbox(): Flow<List<Task>> = taskDao.observeInbox().map { list -> list.map { it.toModel() } }

    fun observeInboxCount(): Flow<Int> = taskDao.observeInboxCount()

    /** Week and Someday lists in manual order (DAT-4, DAT-5). Today has its own screen. */
    fun observeBucket(bucket: Bucket): Flow<List<Task>> = taskDao.observeBucket(bucket.name).map { list -> list.map { it.toModel() } }

    fun observeInProgressCount(): Flow<Int> = taskDao.observeInProgressCount()

    fun observeProjects(): Flow<List<ProjectSummary>> = combine(
        projectDao.observeAll(),
        taskDao.observeActiveProjectTasks(),
    ) { projects, active ->
        val counts = active.groupingBy { it.projectId }.eachCount()
        projects.map { ProjectSummary(it.toModel(), counts[it.id] ?: 0) }
    }

    fun observeActiveProjects(): Flow<List<Project>> = projectDao.observeActive().map { list -> list.map { it.toModel() } }

    fun observeProject(id: ProjectId): Flow<ProjectDetail?> = combine(
        projectDao.observe(id),
        taskDao.observeProjectTasks(id),
    ) { project, tasks -> project?.let { ProjectDetail(it.toModel(), tasks.map { t -> t.toModel() }) } }

    suspend fun project(id: ProjectId): Project? = projectDao.get(id)?.toModel()

    fun observeTags(): Flow<List<Tag>> = taskDao.observeTags().map { list -> list.map { Tag(it.id, it.name) } }

    /** Archive sorted by archive date (ARC-1, TTL-8), paged. */
    fun archive(): Flow<PagingData<Task>> = Pager(PagingConfig(pageSize = PAGE_SIZE)) { taskDao.pagingArchive() }
        .flow.map { page -> page.map { it.toModel() } }

    fun observeArchiveCount(): Flow<Int> = taskDao.observeArchive().map { it.size }

    /** Latest snapshot of each paused task for the Today screen. */
    fun observeLatestSnapshots(): Flow<Map<TaskId, ContextSnapshot>> =
        contentDao.observeLatestSnapshots().map { list -> list.associate { it.taskId to it.toModel() } }

    fun observeHistory(entityId: String): Flow<List<Event>> = db.eventDao().observeHistory(entityId).map { list ->
        list.map { it.toModel() }
    }

    /** Whether there is at least one task: decides between onboarding hints and normal screens. */
    suspend fun isEmpty(): Boolean = taskDao.count() == 0

    companion object {
        const val PAGE_SIZE = 50
    }
}
