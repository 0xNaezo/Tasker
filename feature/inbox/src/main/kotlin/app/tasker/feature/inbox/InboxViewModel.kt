package app.tasker.feature.inbox

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.model.Bucket
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectId
import app.tasker.core.model.Task
import app.tasker.core.ui.action.TaskActions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class InboxUiState(
    val loading: Boolean = true,
    val tasks: List<Task> = emptyList(),
    val projects: List<Project> = emptyList(),
    val triageThreshold: Int = Int.MAX_VALUE,
) {
    /** TTL-9: more than the threshold — offer quick triage. */
    val overflow: Boolean get() = tasks.size > triageThreshold
}

/** Inbox (CAP-5): everything captured without a horizon or a project, newest first. */
@HiltViewModel
class InboxViewModel @Inject constructor(
    tasks: TaskRepository,
    settings: SettingsRepository,
    private val actions: TaskActions,
) : ViewModel() {
    val state: StateFlow<InboxUiState> = combine(tasks.observeInbox(), tasks.observeActiveProjects(), settings.settings) {
            inbox,
            projects,
            s,
        ->
        InboxUiState(
            loading = false,
            tasks = inbox.sortedByDescending { it.createdAt },
            projects = projects,
            triageThreshold = s.inboxTriageThreshold,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), InboxUiState())

    fun toggleDone(task: Task) = launch { actions.toggleDone(task) }

    fun postpone(task: Task, option: PostponeOption) = launch { actions.postpone(task, option) }

    fun move(task: Task, bucket: Bucket) = launch { actions.move(task, bucket) }

    fun setProject(task: Task, projectId: ProjectId?) = launch { actions.setProject(task, projectId) }

    fun moveToNewProject(task: Task, name: String) = launch { actions.moveToNewProject(task, name) }

    fun archive(task: Task) = launch { actions.archive(task) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
