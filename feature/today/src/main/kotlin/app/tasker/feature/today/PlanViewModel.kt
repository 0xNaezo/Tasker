package app.tasker.feature.today

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tasker.core.data.command.StartOutcome
import app.tasker.core.data.plan.DayView
import app.tasker.core.data.plan.PlanService
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.data.repository.TodayRepository
import app.tasker.core.domain.plan.PlanChange
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.model.Bucket
import app.tasker.core.model.ProjectId
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
import kotlinx.coroutines.launch

@Immutable
data class PlanUiState(
    val view: DayView? = null,
    val projectNames: Map<ProjectId, String> = emptyMap(),
    /** The draft was refreshed on opening: what changed since it was built (§10.5, step 1). */
    val change: PlanChange? = null,
    val prepared: Boolean = false,
)

/** "Day plan" (PLN-4…PLN-8, DAT-3): the draft is refreshed on opening, accepted in one tap, edited by hand. */
@HiltViewModel
class PlanViewModel @Inject constructor(
    today: TodayRepository,
    tasks: TaskRepository,
    private val plans: PlanService,
    private val actions: TaskActions,
    private val messenger: Messenger,
) : ViewModel() {
    private val change = MutableStateFlow<PlanChange?>(null)
    private val prepared = MutableStateFlow(false)
    private val overLimit = MutableStateFlow<StartOutcome?>(null)

    val state: StateFlow<PlanUiState> = combine(today.observeToday(), tasks.observeActiveProjects(), change, prepared) {
            view,
            projects,
            change,
            prepared,
        ->
        PlanUiState(view, projects.associate { it.id to it.name }, change, prepared)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlanUiState())

    val wipOutcome: StateFlow<StartOutcome?> = overLimit.asStateFlow()

    init {
        viewModelScope.launch {
            attempt { plans.prepare() }.onSuccess { change.value = it.change }
            prepared.value = true
        }
    }

    /** "Accept" (PLN-6). */
    fun accept() = launch {
        attempt { plans.accept() }
            .onSuccess { messenger.undoable(UiText.Res(R.string.plan_accepted), it.batchId) }
            .onFailure { messenger.info(UiText.Res(UiR.string.error_generic)) }
    }

    fun add(task: Task) = launch { actions.addToPlan(task) }

    fun remove(task: Task) = launch { actions.removeFromPlan(task) }

    /** PLN-5: add a task that does not fit and take another one out. */
    fun replace(add: Task, evict: Task) = launch {
        attempt { plans.replace(add.id, evict.id) }
            .onSuccess { messenger.undoable(UiText.Res(R.string.plan_replaced, add.title), it.batchId) }
            .onFailure { messenger.info(UiText.Res(UiR.string.error_generic)) }
    }

    fun reorder(task: Task, orderedIds: List<TaskId>) = launch {
        attempt { plans.reorder(task.id, orderedIds) }.onFailure { messenger.info(UiText.Res(UiR.string.error_generic)) }
    }

    fun toggleDone(task: Task) = launch { actions.toggleDone(task) }

    fun postpone(task: Task, option: PostponeOption) = launch { actions.postpone(task, option) }

    /** "All to tomorrow" for the tasks that do not fit (PLN-5, scenario 3). */
    fun postponeAll(tasks: List<Task>) = launch { actions.postponeAll(tasks, PostponeOption.Tomorrow) }

    fun start(task: Task) = launch { actions.start(task)?.takeIf { it.overLimit }?.let { overLimit.value = it } }

    fun pause(task: Task, note: String?) = launch { actions.pause(task, note) }

    fun pauseOther(task: Task) {
        overLimit.value = null
        launch { actions.pause(task, null) }
    }

    fun dismissWip() {
        overLimit.value = null
    }

    fun dismissChange() {
        change.value = null
    }

    // DAT-3 answers.
    fun split(task: Task, steps: List<String>) = launch { actions.split(task, steps) }

    fun toSomeday(task: Task) = launch { actions.move(task, Bucket.SOMEDAY) }

    fun archive(task: Task) = launch { actions.archive(task) }

    fun keep(task: Task) = launch { actions.keepAfterQuestion(task) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
