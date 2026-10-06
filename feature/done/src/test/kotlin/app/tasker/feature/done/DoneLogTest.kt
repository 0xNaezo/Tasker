package app.tasker.feature.done

import app.tasker.core.data.repository.DoneDay
import app.tasker.core.data.repository.DoneWeek
import app.tasker.core.domain.summary.DaySummary
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import app.tasker.core.testing.aTask
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import org.junit.Test

class DoneLogTest {
    private fun done(title: String, at: String): Task =
        aTask(title = title, id = title).copy(status = TaskStatus.DONE, completedAt = Instant.parse(at))

    private fun day(day: LocalDate, tasks: List<Task>, planned: List<String> = emptyList(), postponed: Int = 0) = DoneDay(
        day = day,
        tasks = tasks,
        summary = DaySummary.of(day, tasks.map { it.id }, planned, tasks.filter { it.offPlan }.map { it.id }, postponed),
    )

    @Test
    fun `rows go from the latest week and day down, tasks latest first`() {
        val monday = day(date("2026-10-05"), listOf(done("a", "2026-10-05T08:00:00Z"), done("b", "2026-10-05T15:00:00Z")))
        val tuesday = day(date("2026-10-06"), listOf(done("c", "2026-10-06T09:00:00Z")))
        val lastWeek = day(date("2026-09-30"), listOf(done("d", "2026-09-30T10:00:00Z")))
        val weeks = listOf(
            DoneWeek(date("2026-09-28"), listOf(lastWeek)),
            DoneWeek(date("2026-10-05"), listOf(monday, tuesday)),
        )

        val keys = DoneLog.rows(weeks).map { it.key }

        assertThat(keys).containsExactly(
            "week-2026-10-05",
            "day-2026-10-06",
            "task-c",
            "day-2026-10-05",
            "task-b",
            "task-a",
            "week-2026-09-28",
            "day-2026-09-30",
            "task-d",
        ).inOrder()
    }

    @Test
    fun `a day with only postpones has a header and no task rows`() {
        val weeks = listOf(DoneWeek(date("2026-10-05"), listOf(day(date("2026-10-06"), emptyList(), postponed = 2))))

        val rows = DoneLog.rows(weeks)

        assertThat(rows.map { it.contentType }).containsExactly("week", "day").inOrder()
        val numbers = SummaryNumbers.of((rows[1] as DoneRow.DayHeader).day)
        assertThat(numbers.showsDone).isFalse()
        assertThat(numbers.showsPostponed).isTrue()
    }

    @Test
    fun `row keys are unique even if a task were reported twice`() {
        val task = done("a", "2026-10-06T09:00:00Z")
        val weeks = listOf(DoneWeek(date("2026-10-05"), listOf(day(date("2026-10-06"), listOf(task, task)))))

        val keys = DoneLog.rows(weeks).map { it.key }

        assertThat(keys).containsNoDuplicates()
    }

    @Test
    fun `summary splits done into planned and off plan`() {
        val a = done("a", "2026-10-06T08:00:00Z")
        val b = done("b", "2026-10-06T09:00:00Z")
        val captured = done("c", "2026-10-06T10:00:00Z").copy(offPlan = true)
        val today = day(date("2026-10-06"), listOf(a, b, captured), planned = listOf("a", "c"), postponed = 1)
        val monday = day(date("2026-10-05"), listOf(done("d", "2026-10-05T10:00:00Z")), planned = listOf("d"))

        assertThat(SummaryNumbers.of(today)).isEqualTo(SummaryNumbers(done = 3, planned = 1, offPlan = 2, postponed = 1))
        assertThat(SummaryNumbers.of(DoneWeek(date("2026-10-05"), listOf(today, monday))))
            .isEqualTo(SummaryNumbers(done = 4, planned = 2, offPlan = 2, postponed = 1))
    }

    @Test
    fun `this week and last week are named, older weeks are not`() {
        val today = date("2026-10-06")

        assertThat(WeekLabels.relative(date("2026-10-05"), today)).isEqualTo(WeekLabels.Relative.THIS_WEEK)
        assertThat(WeekLabels.relative(date("2026-09-28"), today)).isEqualTo(WeekLabels.Relative.LAST_WEEK)
        assertThat(WeekLabels.relative(date("2026-09-21"), today)).isNull()
        // Sunday still belongs to the week that started on Monday.
        assertThat(WeekLabels.relative(date("2026-10-05"), date("2026-10-11"))).isEqualTo(WeekLabels.Relative.THIS_WEEK)
        assertThat(WeekLabels.weekStart(date("2026-10-11"))).isEqualTo(date("2026-10-05"))
    }

    @Test
    fun `week range within one month`() {
        assertThat(WeekLabels.range(date("2026-10-05"), Locale.ENGLISH, 2026)).isEqualTo("5–11 October")
        assertThat(WeekLabels.range(date("2026-10-05"), RUSSIAN, 2026)).isEqualTo("5–11 октября")
        assertThat(WeekLabels.range(date("2026-10-05"), UKRAINIAN, 2026)).isEqualTo("5–11 жовтня")
    }

    @Test
    fun `week range across two months`() {
        assertThat(WeekLabels.range(date("2026-09-28"), Locale.ENGLISH, 2026)).isEqualTo("28 September – 4 October")
        assertThat(WeekLabels.range(date("2026-09-28"), RUSSIAN, 2026)).isEqualTo("28 сентября – 4 октября")
        assertThat(WeekLabels.range(date("2026-09-28"), UKRAINIAN, 2026)).isEqualTo("28 вересня – 4 жовтня")
    }

    @Test
    fun `week range shows the year outside the current one`() {
        assertThat(WeekLabels.range(date("2025-12-29"), Locale.ENGLISH, 2026)).isEqualTo("29 December 2025 – 4 January 2026")
        assertThat(WeekLabels.range(date("2025-10-06"), Locale.ENGLISH, 2026)).isEqualTo("6–12 October 2025")
        assertThat(WeekLabels.range(date("2025-09-29"), RUSSIAN, 2026)).isEqualTo("29 сентября – 5 октября 2025")
    }

    private companion object {
        val RUSSIAN: Locale = Locale.forLanguageTag("ru")
        val UKRAINIAN: Locale = Locale.forLanguageTag("uk")
    }
}
