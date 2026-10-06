package app.tasker.feature.tasks

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tasker.core.data.command.ProjectCommands
import app.tasker.core.data.command.StartOutcome
import app.tasker.core.data.command.TaskCommands
import app.tasker.core.data.repository.ProjectSummary
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.model.Bucket
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectId
import app.tasker.core.model.ProjectStatus
import app.tasker.core.model.Task
import app.tasker.core.model.TaskId
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.action.TaskActions
import app.tasker.core.ui.message.Messenger
import app.tasker.core.ui.text.UiText
import app.tasker.core.ui.util.attempt
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** List filters of the Week and Someday tabs. */
@Immutable
data class TaskFilter(val tag: String? = null, val projectId: ProjectId? = null, val withDeadline: Boolean = false) {
    val isActive: Boolean get() = tag != null || projectId != null || withDeadline

    fun matches(task: Task): Boolean =
        (tag == null || task.tags.any { it.equals(tag, ignoreCase = true) }) &&
            (projectId == null || task.projectId == projectId) &&
            (!withDeadline || task.deadline != null)
}

@Immutable
data class TasksUiState(
    val loading: Boolean = true,
    val week: List<Task> = emptyList(),
    val someday: List<Task> = emptyList(),
    val projects: List<ProjectSummary> = emptyList(),
    val filter: TaskFilter = TaskFilter(),
) {
    val activeProjects: List<Project> get() = projects.map { it.project }.filter { it.status == ProjectStatus.ACTIVE }

    val projectNames: Map<ProjectId, String> get() = projects.associate { it.project.id to it.project.name }

    /** Tags used by the listed tasks, for filter chips. */
    val tags: List<String> get() = (week + someday).flatMap { it.tags }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
}

/** "Tasks" tab (DAT-4, DAT-5, EXE-4, EXE-5): horizons, manual order, filters and projects. */
@HiltViewModel
class TasksViewModel @Inject constructor(
    tasks: TaskRepository,
    private val taskCommands: TaskCommands,
    private val projectCommands: ProjectCommands,
    private val actions: TaskActions,
    private val messenger: Messenger,
) : ViewModel() {
    private val filter = MutableStateFlow(TaskFilter())
    private val overLimit = MutableStateFlow<StartOutcome?>(null)

    val wipOutcome: StateFlow<StartOutcome?> = overLimit.asStateFlow()

    val state: StateFlow<TasksUiState> = combine(
        tasks.observeBucket(Bucket.WEEK),
        tasks.observeBucket(Bucket.SOMEDAY),
        tasks.observeProjects(),
        filter,
    ) { week, someday, projects, filter ->
        TasksUiState(
            loading = false,
            week = week,
            someday = someday,
            projects = projects.sortedWith(compareBy({ it.project.status.ordinal }, { it.project.position })),
            filter = filter,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TasksUiState())

    fun setFilter(value: TaskFilter) = filter.update { value }

    fun toggleDone(task: Task) = launch { actions.toggleDone(task) }

    fun postpone(task: Task, option: PostponeOption) = launch { actions.postpone(task, option) }

    fun move(task: Task, bucket: Bucket?) = launch { actions.move(task, bucket) }

    fun setProject(task: Task, projectId: ProjectId?) = launch { actions.setProject(task, projectId) }

    fun moveToNewProject(task: Task, name: String) = launch { actions.moveToNewProject(task, name) }

    fun archive(task: Task) = launch { actions.archive(task) }

    fun addToPlan(task: Task) = launch { actions.addToPlan(task) }

    fun start(task: Task) = launch { actions.start(task)?.takeIf { it.overLimit }?.let { overLimit.value = it } }

    fun pause(task: Task, note: String?) = launch { actions.pause(task, note) }

    fun pauseOther(task: Task) {
        overLimit.value = null
        launch { actions.pause(task, null) }
    }

    fun dismissWip() {
        overLimit.value = null
    }

    /** DAT-5: [orderedIds] is the visible order after the drag. */
    fun reorder(taskId: TaskId, orderedIds: List<TaskId>) = launch {
        attempt { taskCommands.reorder(taskId, orderedIds) }.onFailure { messenger.info(UiText.Res(UiR.string.error_generic)) }
    }

    fun reorderProjects(projectId: ProjectId, orderedIds: List<ProjectId>) = launch {
        attempt { projectCommands.reorder(projectId, orderedIds) }.onFailure { messenger.info(UiText.Res(UiR.string.error_generic)) }
    }

    fun createProject(name: String, onCreated: (ProjectId) -> Unit) = launch {
        attempt { projectCommands.create(name) }
            .onSuccess {
                messenger.undoable(UiText.Res(R.string.tasks_project_created, it.value.name), it.batchId)
                onCreated(it.value.id)
            }
            .onFailure { messenger.info(UiText.Res(UiR.string.error_generic)) }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
