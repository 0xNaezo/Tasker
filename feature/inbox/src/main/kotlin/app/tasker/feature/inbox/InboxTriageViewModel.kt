package app.tasker.feature.inbox

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tasker.core.data.command.ProjectCommands
import app.tasker.core.data.di.ApplicationScope
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.data.review.ReviewService
import app.tasker.core.model.EntityType
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectId
import app.tasker.core.model.ReviewDecision
import app.tasker.core.model.ReviewKind
import app.tasker.core.model.Task
import app.tasker.core.model.TaskId
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.message.Messenger
import app.tasker.core.ui.text.UiText
import app.tasker.core.ui.util.attempt
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class TriageUiState(
    val loading: Boolean = true,
    val current: Task? = null,
    /** 1-based position of [current] among the cards of this session. */
    val position: Int = 0,
    val total: Int = 0,
    val projects: List<Project> = emptyList(),
)

/**
 * Quick Inbox triage (TTL-9): one card, one movement — Today, Week, Someday, a project or the archive. Skipped cards
 * are not counted as review skips (that is only for the relevance review).
 */
@HiltViewModel
class InboxTriageViewModel @Inject constructor(
    tasks: TaskRepository,
    private val review: ReviewService,
    private val projects: ProjectCommands,
    private val messenger: Messenger,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val handled = MutableStateFlow<Set<TaskId>>(emptySet())
    private val session = CompletableDeferred<String>()

    val state: StateFlow<TriageUiState> = combine(tasks.observeInbox(), tasks.observeActiveProjects(), handled) {
            inbox,
            projects,
            handled,
        ->
        val remaining = inbox.filter { it.id !in handled }.sortedBy { it.createdAt }
        TriageUiState(
            loading = false,
            current = remaining.firstOrNull(),
            position = handled.size + 1,
            total = handled.size + remaining.size,
            projects = projects,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TriageUiState())

    init {
        viewModelScope.launch { attempt { review.startSession(ReviewKind.INBOX_TRIAGE) }.onSuccess { session.complete(it) } }
    }

    fun shown(task: Task) {
        viewModelScope.launch {
            val id = session.await()
            attempt { review.cardShown(id, EntityType.TASK, task.id) }
        }
    }

    fun decide(task: Task, decision: ReviewDecision, projectId: ProjectId? = null) {
        handled.update { it + task.id }
        viewModelScope.launch {
            attempt { review.triage(task.id, decision, projectId) }
                .onSuccess { messenger.undoable(UiText.Res(messageFor(decision), task.title), it.batchId) }
                .onFailure {
                    handled.update { ids -> ids - task.id }
                    messenger.info(UiText.Res(UiR.string.error_generic))
                }
        }
    }

    fun newProject(task: Task, name: String) {
        viewModelScope.launch {
            attempt { projects.create(name) }
                .onSuccess { decide(task, ReviewDecision.PROJECT, it.value.id) }
                .onFailure { messenger.info(UiText.Res(UiR.string.error_generic)) }
        }
    }

    fun skip(task: Task) = handled.update { it + task.id }

    override fun onCleared() {
        if (session.isCompleted) {
            val id = session.getCompleted()
            appScope.launch { attempt { review.endSession(id) } }
        }
    }

    private fun messageFor(decision: ReviewDecision): Int = when (decision) {
        ReviewDecision.ARCHIVE -> UiR.string.undo_archived
        ReviewDecision.PROJECT -> UiR.string.undo_project_set
        else -> UiR.string.undo_moved
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
