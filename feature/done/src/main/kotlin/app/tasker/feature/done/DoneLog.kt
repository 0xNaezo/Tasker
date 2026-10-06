package app.tasker.feature.done

import androidx.compose.runtime.Immutable
import app.tasker.core.data.repository.DoneDay
import app.tasker.core.data.repository.DoneWeek
import app.tasker.core.model.Task
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** A row of the done log (LOG-1): a week with its summary, a day with its summary, or a task completed that day. */
@Immutable
sealed interface DoneRow {
    val key: String
    val contentType: String

    data class WeekHeader(val week: DoneWeek) : DoneRow {
        override val key: String get() = "week-${week.weekStart}"
        override val contentType: String get() = "week"
    }

    data class DayHeader(val day: DoneDay) : DoneRow {
        override val key: String get() = "day-${day.day}"
        override val contentType: String get() = "day"
    }

    data class Item(val task: Task, val day: LocalDate) : DoneRow {
        override val key: String get() = "task-${task.id}"
        override val contentType: String get() = "task"
    }
}

/**
 * What a day or week summary says (LOG-2, interpretation 11): done, of which planned and off-plan, and postponed.
 * Only counts of the period itself: no streaks, scores or comparisons with other periods (principle 6).
 */
@Immutable
data class SummaryNumbers(val done: Int, val planned: Int, val offPlan: Int, val postponed: Int) {
    /** The "done" part is shown only when something was done; a day with only postpones does not read "Done: 0". */
    val showsDone: Boolean get() = done > 0
    val showsPostponed: Boolean get() = postponed > 0

    companion object {
        fun of(day: DoneDay): SummaryNumbers =
            SummaryNumbers(day.summary.done, day.summary.donePlanned, day.summary.doneOffPlan, day.summary.postponed)

        fun of(week: DoneWeek): SummaryNumbers = SummaryNumbers(week.done, week.donePlanned, week.doneOffPlan, week.postponed)
    }
}

/** Flattens the weeks of [app.tasker.core.data.repository.DoneRepository] into list rows, latest first. */
object DoneLog {
    fun rows(weeks: List<DoneWeek>): List<DoneRow> = buildList {
        weeks.sortedByDescending { it.weekStart }.forEach { week ->
            add(DoneRow.WeekHeader(week))
            week.days.sortedByDescending { it.day }.forEach { day ->
                add(DoneRow.DayHeader(day))
                day.tasks.sortedByDescending { it.completedAt }.forEach { add(DoneRow.Item(it, day.day)) }
            }
        }
    }.distinctBy { it.key }
}

/** Titles of weeks (Monday to Sunday, as the log groups them): "this week", "last week" or a date range. */
object WeekLabels {
    enum class Relative { THIS_WEEK, LAST_WEEK }

    private const val LAST_DAY_OFFSET = 6L

    fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun relative(weekStart: LocalDate, today: LocalDate): Relative? {
        val current = weekStart(today)
        return when (weekStart) {
            current -> Relative.THIS_WEEK
            current.minusWeeks(1) -> Relative.LAST_WEEK
            else -> null
        }
    }

    /**
     * "5–11 October", "28 September – 4 October"; the year is added when the week is not in [currentYear]:
     * "29 December 2025 – 4 January 2026". Month names take the form the language uses after a day number.
     */
    fun range(weekStart: LocalDate, locale: Locale, currentYear: Int): String {
        val end = weekStart.plusDays(LAST_DAY_OFFSET)
        val withYear = weekStart.year != currentYear || end.year != currentYear
        val dayMonth = DateTimeFormatter.ofPattern("d MMMM", locale)
        val full = if (withYear) DateTimeFormatter.ofPattern("d MMMM y", locale) else dayMonth
        return when {
            weekStart.year != end.year -> "${weekStart.format(full)} – ${end.format(full)}"
            weekStart.month == end.month -> "${weekStart.dayOfMonth}–${end.format(full)}"
            else -> "${weekStart.format(dayMonth)} – ${end.format(full)}"
        }
    }
}
