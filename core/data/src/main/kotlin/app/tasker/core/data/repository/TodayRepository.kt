package app.tasker.core.data.repository

import app.tasker.core.data.mapper.toEpochDayLong
import app.tasker.core.data.mapper.toMillis
import app.tasker.core.data.mapper.toModel
import app.tasker.core.data.plan.DayView
import app.tasker.core.data.plan.DayViews
import app.tasker.core.data.review.ReviewService
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.domain.capacity.CapacityCalculator
import app.tasker.core.domain.port.BusyTime
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AppSettings
import app.tasker.core.model.ContextSnapshot
import app.tasker.core.model.DayPlan
import app.tasker.core.model.Task
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** The Today and Plan screens' state for the current logical day; follows the day boundary and the clock. */
@Singleton
class TodayRepository @Inject constructor(
    private val clock: DayClock,
    private val settings: SettingsRepository,
    private val busy: BusyTimeRepository,
    private val calculator: CapacityCalculator,
    private val review: ReviewService,
    private val tasks: TaskRepository,
    private val db: TaskerDatabase,
) {
    /** Emits "now" every minute: capacity of today shrinks with time (§10.1). */
    fun ticker(periodMillis: Long = TICK_MILLIS): Flow<Instant> = flow {
        while (true) {
            emit(clock.now())
            delay(periodMillis)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeToday(): Flow<DayView> = ticker().map { clock.logicalDay(it) }.distinctUntilChanged().flatMapLatest { observeDay(it) }

    fun observeDay(day: LocalDate): Flow<DayView> {
        val key = day.toEpochDayLong()
        val planDao = db.planDao()
        val plan: Flow<DayPlan?> = combine(planDao.observePlan(key), planDao.observeItems(key)) { entity, items -> entity?.toModel(items) }
        val busyTime: Flow<Pair<AppSettings, BusyTime>> = combine(settings.settings, busy.changes()) { s, _ -> s to busy.busyFor(day, s) }
        val doneToday: Flow<List<Task>> = db.taskDao()
            .observeDone(clock.dayStart(day).toMillis(), clock.dayEnd(day).toMillis())
            .map { list -> list.map { it.toModel() } }
        val taskState = combine(tasks.observeActive(), doneToday, tasks.observeLatestSnapshots()) { active, done, snapshots ->
            TaskState(active, done, snapshots)
        }
        val counters = combine(review.observeQueue(), tasks.observeInboxCount()) { queue, inbox -> queue.size to inbox }
        return combine(busyTime, taskState, plan, counters, ticker()) {
                (current, busyNow),
                state,
                dayPlan,
                (reviewCount, inboxCount),
                now,
            ->
            val capacity = calculator.compute(day, current, busyNow.intervals, busyNow.calendarAvailable, now)
            DayViews.build(
                day = day,
                now = now,
                zone = clock.zone(),
                settings = current,
                capacity = capacity,
                plan = dayPlan,
                activeTasks = state.active,
                planTasks = (state.active + state.done).associateBy { it.id },
                snapshots = state.snapshots,
                reviewCount = reviewCount,
                inboxCount = inboxCount,
            )
        }
    }

    private data class TaskState(val active: List<Task>, val done: List<Task>, val snapshots: Map<String, ContextSnapshot>)

    companion object {
        const val TICK_MILLIS = 60_000L
    }
}
