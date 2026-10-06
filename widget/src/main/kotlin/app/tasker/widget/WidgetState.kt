package app.tasker.widget

import app.tasker.core.data.plan.DayView
import app.tasker.core.data.repository.TodayRepository
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.model.CandidateGroup
import app.tasker.core.model.TaskId
import app.tasker.core.model.TaskStatus
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** What the widget shows: a flat snapshot of Today, so the widget never repeats the planning rules (§10, §14.2). */
internal sealed interface WidgetState {
    /** App lock is on: capture works, existing tasks are not shown (tech plan §13). */
    data object Locked : WidgetState

    data class Day(
        /** Work in progress first, then the unfinished plan or, without an accepted plan, the candidates (PLN-9). */
        val rows: List<WidgetRow>,
        val planAccepted: Boolean,
        val planDone: Int,
        val planTotal: Int,
    ) : WidgetState

    companion object {
        /** Enough for the tallest widget; the rest is one tap away in the app. */
        const val MAX_ROWS = 30

        fun of(view: DayView): Day {
            val work = view.work.map { entry ->
                val mark = if (entry.task.status == TaskStatus.IN_PROGRESS) RowMark.IN_PROGRESS else RowMark.PAUSED
                WidgetRow(entry.task.id, entry.task.title, mark)
            }
            val working = work.mapTo(HashSet()) { it.taskId }
            val rest = if (view.isAccepted) {
                view.entries.filter { !it.isDone && it.task.id !in working }.map { WidgetRow(it.task.id, it.task.title, markOf(it.group)) }
            } else {
                view.unplannedCandidates.filter { it.task.id !in working }.map { WidgetRow(it.task.id, it.task.title, markOf(it.group)) }
            }
            return Day(
                rows = (work + rest).take(MAX_ROWS),
                planAccepted = view.isAccepted,
                planDone = view.entries.count { it.isDone },
                planTotal = view.entries.size,
            )
        }

        private fun markOf(group: CandidateGroup?): RowMark = if (group == CandidateGroup.OVERDUE) RowMark.OVERDUE else RowMark.NONE
    }
}

internal enum class RowMark { IN_PROGRESS, PAUSED, OVERDUE, NONE }

internal data class WidgetRow(val taskId: TaskId, val title: String, val mark: RowMark)

/** The widget's state as it changes: the lock hides tasks at once, Room changes redraw a running widget session. */
internal class WidgetStates @Inject constructor(private val today: TodayRepository, private val settings: SettingsRepository) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<WidgetState> = settings.settings.map { it.biometricLock }
        .distinctUntilChanged()
        .flatMapLatest { locked -> if (locked) flowOf(WidgetState.Locked) else today.observeToday().map(WidgetState::of) }
        .distinctUntilChanged()
}
