package app.tasker.feature.done

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tasker.core.data.repository.DoneRepository
import app.tasker.core.data.repository.DoneWeek
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.ProjectId
import app.tasker.core.model.Task
import app.tasker.core.ui.action.TaskActions
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class DoneUiState(
    /** Weeks with completions or postpones, latest first; `null` while loading. */
    val weeks: List<DoneWeek>? = null,
    /** Names of all projects, completed and archived ones included: done tasks often belong to finished projects. */
    val projectNames: Map<ProjectId, String> = emptyMap(),
    /** How many weeks back the log reaches. */
    val weeksShown: Int = DoneRepository.DEFAULT_WEEKS,
)

/** "Done" (§14.2, LOG-1, LOG-2, EXC-7): the log by logical days and weeks; completion is undone right from it. */
@HiltViewModel
class DoneViewModel @Inject constructor(
    done: DoneRepository,
    tasks: TaskRepository,
    clock: DayClock,
    private val actions: TaskActions,
) : ViewModel() {
    private val range = MutableStateFlow(DoneRepository.DEFAULT_WEEKS)

    /** The query is bound to the logical day, so it is restarted when the day boundary passes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<DoneUiState> = combine(range, logicalDays(clock)) { weeks, _ -> weeks }
        .flatMapLatest { weeks -> done.observeWeeks(weeks).map { log -> weeks to log } }
        .combine(tasks.observeProjects()) { (weeks, log), projects ->
            DoneUiState(log, projects.associate { it.project.id to it.project.name }, weeks)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DoneUiState())

    /** Extends the log by [DoneRepository.DEFAULT_WEEKS] more weeks back. */
    fun showEarlier() {
        range.update { it + DoneRepository.DEFAULT_WEEKS }
    }

    /** "Mark as not done" (EXC-7): back to Open, with Undo in the snackbar. */
    fun reopen(task: Task) {
        viewModelScope.launch { actions.reopen(task) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val TICK_MS = 60_000L

        fun logicalDays(clock: DayClock): Flow<LocalDate> = flow {
            while (true) {
                emit(clock.today())
                delay(TICK_MS)
            }
        }.distinctUntilChanged()
    }
}
