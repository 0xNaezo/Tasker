package app.tasker.feature.tasks

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tasker.core.data.command.ProjectArchivePreview
import app.tasker.core.data.command.ProjectCommands
import app.tasker.core.data.command.TxResult
import app.tasker.core.data.repository.ProjectDetail
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.model.Bucket
import app.tasker.core.model.ProjectId
import app.tasker.core.model.Task
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.action.TaskActions
import app.tasker.core.ui.message.Messenger
import app.tasker.core.ui.text.UiText
import app.tasker.core.ui.util.attempt
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ProjectUiState(val loading: Boolean = true, val detail: ProjectDetail? = null)

/** Project card (EXE-4, EXE-5, TTL-7): outcome, steps, completion and archiving with its tasks. */
@HiltViewModel(assistedFactory = ProjectViewModel.Factory::class)
class ProjectViewModel @AssistedInject constructor(
    @Assisted private val projectId: ProjectId,
    tasks: TaskRepository,
    private val projects: ProjectCommands,
    private val actions: TaskActions,
    private val messenger: Messenger,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(projectId: ProjectId): ProjectViewModel
    }

    val state: StateFlow<ProjectUiState> = tasks.observeProject(projectId)
        .map { ProjectUiState(loading = false, detail = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProjectUiState())

    private val preview = MutableStateFlow<ProjectArchivePreview?>(null)

    /** What archiving would do (TTL-7: tasks with a future deadline stay open outside the project). */
    val archivePreview: StateFlow<ProjectArchivePreview?> = preview.asStateFlow()

    fun rename(name: String) = command(R.string.tasks_project_renamed) { projects.rename(projectId, name) }

    fun setOutcome(outcome: String?) = command(R.string.tasks_project_outcome_saved) { projects.setOutcome(projectId, outcome) }

    fun complete() = command(R.string.tasks_project_completed) { projects.complete(projectId) }

    fun reopen() = command(R.string.tasks_project_reopened) { projects.reopen(projectId) }

    fun requestArchive() {
        viewModelScope.launch { attempt { projects.previewArchive(projectId) }.onSuccess { preview.value = it } }
    }

    fun cancelArchive() {
        preview.value = null
    }

    fun confirmArchive() {
        preview.value = null
        command(R.string.tasks_project_archived) { projects.archive(projectId) }
    }

    fun restore(withTasks: Boolean) = command(R.string.tasks_project_restored) { projects.restore(projectId, withTasks) }

    /** "Add step" (EXE-5): a regular capture inside the project. */
    fun addStep(text: String) {
        if (text.isBlank()) return
        command(R.string.tasks_step_added) { projects.addStep(projectId, text) }
    }

    fun toggleDone(task: Task) = launch { actions.toggleDone(task) }

    fun postpone(task: Task, option: PostponeOption) = launch { actions.postpone(task, option) }

    fun move(task: Task, bucket: Bucket?) = launch { actions.move(task, bucket) }

    fun removeFromProject(task: Task) = launch { actions.setProject(task, null) }

    fun archive(task: Task) = launch { actions.archive(task) }

    fun addToPlan(task: Task) = launch { actions.addToPlan(task) }

    private fun command(message: Int, block: suspend () -> TxResult<*>) = launch {
        val name = state.value.detail?.project?.name.orEmpty()
        attempt(block)
            .onSuccess { messenger.undoable(UiText.Res(message, name), it.batchId) }
            .onFailure { messenger.info(UiText.Res(UiR.string.error_generic)) }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
