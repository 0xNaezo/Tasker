package app.tasker.feature.today

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tasker.core.ai.AiController
import app.tasker.core.data.command.StartOutcome
import app.tasker.core.data.plan.DayView
import app.tasker.core.data.repository.DailyPrompt
import app.tasker.core.data.repository.DailyPrompts
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.data.repository.TodayRepository
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.model.ProjectId
import app.tasker.core.model.Task
import app.tasker.core.ui.action.TaskActions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class TodayUiState(
    val view: DayView? = null,
    val projectNames: Map<ProjectId, String> = emptyMap(),
    val reviewBannerDismissed: Boolean = false,
    val inboxBannerDismissed: Boolean = false,
    /** Offer AI help once, after the first tasks rather than at start (tech plan §17.1). */
    val aiOffer: Boolean = false,
)

/** "Today" (§14.2): capacity, work in progress, the accepted plan or the candidates, daily banners. */
@HiltViewModel
class TodayViewModel @Inject constructor(
    today: TodayRepository,
    tasks: TaskRepository,
    private val prompts: DailyPrompts,
    private val actions: TaskActions,
    private val ai: AiController,
) : ViewModel() {
    private val aiOffer = combine(ai.state, tasks.observeActive().map { it.size }.distinctUntilChanged()) { ai, count ->
        !ai.hasConsent && !ai.consentPromptDismissed && ai.availableModes.isNotEmpty() && count >= AI_OFFER_AFTER_TASKS
    }.distinctUntilChanged()

    val state: StateFlow<TodayUiState> = combine(
        today.observeToday(),
        tasks.observeActiveProjects(),
        prompts.observeDismissed(DailyPrompt.REVIEW),
        prompts.observeDismissed(DailyPrompt.INBOX_TRIAGE),
        aiOffer,
    ) { view, projects, reviewDismissed, inboxDismissed, offer ->
        TodayUiState(view, projects.associate { it.id to it.name }, reviewDismissed, inboxDismissed, offer)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TodayUiState())

    private val overLimit = MutableStateFlow<StartOutcome?>(null)

    /** Set when a start went over the soft WIP limit (DAT-7). */
    val wipOutcome: StateFlow<StartOutcome?> = overLimit.asStateFlow()

    fun toggleDone(task: Task) = launch { actions.toggleDone(task) }

    fun postpone(task: Task, option: PostponeOption) = launch { actions.postpone(task, option) }

    fun start(task: Task) = launch {
        actions.start(task)?.takeIf { it.overLimit }?.let { overLimit.value = it }
    }

    fun pause(task: Task, note: String?) = launch { actions.pause(task, note) }

    fun pauseOther(task: Task) {
        overLimit.value = null
        launch { actions.pause(task, null) }
    }

    fun dismissWip() {
        overLimit.value = null
    }

    fun addToPlan(task: Task) = launch { actions.addToPlan(task) }

    fun removeFromPlan(task: Task) = launch { actions.removeFromPlan(task) }

    fun dismiss(prompt: DailyPrompt) = launch { prompts.dismiss(prompt) }

    /** "Not now" on the AI card: it is not shown again; AI stays reachable in settings. */
    fun dismissAiOffer() = launch { ai.dismissConsentPrompt() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val AI_OFFER_AFTER_TASKS = 3
    }
}
