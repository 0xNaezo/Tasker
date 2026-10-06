package app.tasker.feature.review

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tasker.core.data.command.TxResult
import app.tasker.core.data.di.ApplicationScope
import app.tasker.core.data.review.ReviewService
import app.tasker.core.domain.review.ReviewItem
import app.tasker.core.model.EntityType
import app.tasker.core.model.ReviewDecision
import app.tasker.core.model.ReviewKind
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
data class ReviewUiState(
    val loading: Boolean = true,
    val current: ReviewItem? = null,
    val position: Int = 0,
    val total: Int = 0,
)

/**
 * Relevance review (TTL-4…TTL-6): one card at a time with its reason and three actions. A card counts as shown the
 * moment it appears; cards left undecided by the end of the day become skips (rule R4), so skipping needs no extra step.
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val review: ReviewService,
    private val messenger: Messenger,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val handled = MutableStateFlow<Set<String>>(emptySet())
    private val session = CompletableDeferred<String>()

    val state: StateFlow<ReviewUiState> = combine(review.observeQueue(), handled) { queue, handled ->
        val remaining = queue.filter { it.key !in handled }
        ReviewUiState(
            loading = false,
            current = remaining.firstOrNull(),
            position = handled.size + 1,
            total = handled.size + remaining.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ReviewUiState())

    init {
        viewModelScope.launch { attempt { review.startSession(ReviewKind.RELEVANCE) }.onSuccess { session.complete(it) } }
    }

    fun shown(item: ReviewItem) {
        viewModelScope.launch {
            val id = session.await()
            when (item) {
                is ReviewItem.TaskCard -> attempt { review.cardShown(id, EntityType.TASK, item.task.id) }
                is ReviewItem.ProjectCard -> attempt { review.cardShown(id, EntityType.PROJECT, item.project.id) }
            }
        }
    }

    fun decide(item: ReviewItem, decision: ReviewDecision) = act(item, messageFor(decision), titleOf(item)) {
        when (item) {
            is ReviewItem.TaskCard -> review.decideTask(item.task.id, decision)
            is ReviewItem.ProjectCard -> review.decideProject(item.project.id, decision)
        }
    }

    /** EXE-5: "add step" for a project without a next step. */
    fun addStep(item: ReviewItem.ProjectCard, text: String) {
        if (text.isBlank()) return
        act(item, R.string.review_step_added, item.project.name) { review.addProjectStep(item.project.id, text) }
    }

    fun skip(item: ReviewItem) = handled.update { it + item.key }

    override fun onCleared() {
        if (session.isCompleted) {
            val id = session.getCompleted()
            appScope.launch { attempt { review.endSession(id) } }
        }
    }

    private fun act(item: ReviewItem, message: Int, title: String, block: suspend () -> TxResult<*>) {
        handled.update { it + item.key }
        viewModelScope.launch {
            attempt(block)
                .onSuccess { messenger.undoable(UiText.Res(message, title), it.batchId) }
                .onFailure {
                    handled.update { keys -> keys - item.key }
                    messenger.info(UiText.Res(UiR.string.error_generic))
                }
        }
    }

    private fun titleOf(item: ReviewItem): String = when (item) {
        is ReviewItem.TaskCard -> item.task.title
        is ReviewItem.ProjectCard -> item.project.name
    }

    private fun messageFor(decision: ReviewDecision): Int = when (decision) {
        ReviewDecision.RELEVANT -> R.string.review_kept
        ReviewDecision.ARCHIVE -> UiR.string.undo_archived
        ReviewDecision.COMPLETE_PROJECT -> R.string.review_project_completed
        else -> UiR.string.undo_moved
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
