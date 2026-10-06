package app.tasker.core.data.repository

import app.tasker.core.data.mapper.toEpochDayLong
import app.tasker.core.data.mapper.toLocalDate
import app.tasker.core.data.mapper.toMillis
import app.tasker.core.data.mapper.toModel
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.domain.summary.DaySummary
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.PlanState
import app.tasker.core.model.Task
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class DoneDay(val day: LocalDate, val tasks: List<Task>, val summary: DaySummary)

/** Week summary: sums of the day summaries; no streaks, ratings or comparisons (principle 6, LOG-2). */
data class DoneWeek(val weekStart: LocalDate, val days: List<DoneDay>) {
    val done: Int get() = days.sumOf { it.summary.done }
    val donePlanned: Int get() = days.sumOf { it.summary.donePlanned }
    val doneOffPlan: Int get() = days.sumOf { it.summary.doneOffPlan }
    val postponed: Int get() = days.sumOf { it.summary.postponed }
}

/** Done log grouped by logical days and weeks (LOG-1, LOG-2). */
@Singleton
class DoneRepository @Inject constructor(
    private val clock: DayClock,
    private val db: TaskerDatabase,
) {
    fun observeWeeks(weeks: Int = DEFAULT_WEEKS): Flow<List<DoneWeek>> {
        val today = clock.today()
        val from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((weeks - 1).toLong())
        return db.taskDao().observeDone(clock.dayStart(from).toMillis(), clock.dayEnd(today).toMillis()).map { rows ->
            val done = rows.map { it.toModel() }
            val days = summarize(done, from, today)
            days.groupBy { it.day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
                .map { (start, list) -> DoneWeek(start, list.sortedByDescending { it.day }) }
                .sortedByDescending { it.weekStart }
        }
    }

    private suspend fun summarize(done: List<Task>, from: LocalDate, to: LocalDate): List<DoneDay> {
        val planDao = db.planDao()
        val plans = planDao.plansBetween(from.toEpochDayLong(), to.toEpochDayLong()).filter { it.state == PlanState.ACCEPTED }
        val accepted = plans.map { it.date }.toSet()
        val planned = planDao.itemsBetween(from.toEpochDayLong(), to.toEpochDayLong())
            .filter { it.date in accepted }
            .groupBy({ it.date.toLocalDate() }, { it.taskId })
        val byDay = done.groupBy { clock.logicalDay(checkNotNull(it.completedAt)) }
        val days = (byDay.keys + planned.keys).filter { !it.isBefore(from) && !it.isAfter(to) }.toSortedSet()
        return days.map { day ->
            val tasks = byDay[day].orEmpty().sortedByDescending { it.completedAt }
            val postponed = db.eventDao().postponedTaskCount(clock.dayStart(day).toMillis(), clock.dayEnd(day).toMillis())
            DoneDay(
                day = day,
                tasks = tasks,
                summary = DaySummary.of(
                    day = day,
                    doneTaskIds = tasks.map { it.id },
                    acceptedPlanTaskIds = planned[day].orEmpty(),
                    offPlanTaskIds = tasks.filter { it.offPlan }.map { it.id },
                    postponedTaskCount = postponed,
                ),
            )
        }.filter { it.tasks.isNotEmpty() || it.summary.postponed > 0 }
    }

    companion object {
        const val DEFAULT_WEEKS = 4
    }
}
