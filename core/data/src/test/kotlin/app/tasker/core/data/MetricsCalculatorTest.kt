package app.tasker.core.data

import app.tasker.core.data.metrics.MetricsCalculator
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.model.CaptureChannel
import app.tasker.core.model.ReviewKind
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MetricsCalculatorTest {
    private val env = DataEnv(start = MONDAY.atTime(9, 0))
    private val calculator = MetricsCalculator(env.db, env.clock)

    @After
    fun tearDown() = env.close()

    @Test
    fun `a week of use adds up to the success metrics`() = runTest {
        // Monday: three tasks for today and one shared article; the plan is accepted.
        env.maintenance.recordActivity()
        val call = env.add("Call the bank today")
        env.add("Write the report today")
        val milk = env.add("Buy milk today", CaptureChannel.WIDGET)
        val article = env.add("Read the article", CaptureChannel.SHARE)
        env.plans.accept()
        env.tasks.complete(call.id)
        env.tasks.postpone(milk.id, PostponeOption.Tomorrow)
        session(ReviewKind.RELEVANCE, Duration.ofMinutes(4))

        // Tuesday: the report was carried over; a triage session was left open for two hours.
        env.time.set(MONDAY.plusDays(1).atTime(10, 0))
        env.maintenance.catchUp()
        env.maintenance.recordActivity()
        env.tasks.complete(milk.id)
        env.tasks.archive(article.id)
        session(ReviewKind.INBOX_TRIAGE, Duration.ofHours(2))

        // Wednesday: a task left by its relevance checks; a session that never ended.
        env.time.set(MONDAY.plusDays(2).atTime(10, 0))
        env.maintenance.recordActivity()
        val idea = env.add("Old idea")
        archiveByTtl(idea.id)
        env.review.startSession(ReviewKind.RELEVANCE)

        val week = calculator.week(MONDAY)

        assertThat(week.counters).containsExactlyEntriesIn(
            mapOf(
                "format" to 1L,
                "maintenance.sessions" to 2L,
                "maintenance.seconds" to 4 * 60L + MetricsCalculator.MAX_SESSION.seconds,
                "plan.pending" to 0L,
                "plan.done" to 1L,
                "plan.carried_over" to 1L,
                "plan.removed" to 1L,
                "plan.items" to 2L,
                "finished.tasks" to 4L,
                "postpone.0" to 3L,
                "postpone.1" to 1L,
                "postpone.2" to 0L,
                "postpone.3_plus" to 0L,
                "created.bar" to 3L,
                "created.widget" to 1L,
                "created.tile" to 0L,
                "created.shortcut" to 0L,
                "created.share" to 1L,
                "created.voice" to 0L,
                "created.telegram" to 0L,
                "created.github" to 0L,
                "created.import" to 0L,
                "created.total" to 5L,
                "archived.ttl" to 1L,
                "active.days" to 3L,
            ),
        )
        assertThat(week.values).containsExactly("plan.done_share", 0.5, "postpone.median", 0.0, "archived.ttl_share", 0.2)
    }

    @Test
    fun `keys fit the format the backend accepts`() = runTest {
        val week = calculator.week(MONDAY)

        val keys = week.counters.keys + week.values.keys
        assertThat(keys.size).isAtMost(MAX_ENTRIES)
        keys.forEach { assertThat(it).matches(KEY) }
    }

    @Test
    fun `a quiet week has counts of zero and no shares`() = runTest {
        val week = calculator.week(MONDAY.plusWeeks(1))

        assertThat(week.counters.filterKeys { it != "format" }.values.toSet()).containsExactly(0L)
        assertThat(week.values).isEmpty()
    }

    @Test
    fun `weeks follow logical days`() = runTest {
        // 03:30 on Monday still belongs to Sunday: the task counts in the previous week.
        env.time.set(MONDAY.atTime(3, 30))
        env.add("Late night thought")
        env.time.set(MONDAY.plusWeeks(1).atTime(3, 30))
        env.add("Another late thought")

        assertThat(calculator.week(MONDAY.minusWeeks(1)).counters["created.total"]).isEqualTo(1)
        assertThat(calculator.week(MONDAY).counters["created.total"]).isEqualTo(1)
        assertThat(calculator.lastFullWeek()).isEqualTo(MONDAY.minusWeeks(1))
        env.time.set(MONDAY.plusWeeks(1).atTime(4, 0))
        assertThat(calculator.lastFullWeek()).isEqualTo(MONDAY)
    }

    @Test
    fun `the first week is the week of the first use`() = runTest {
        assertThat(calculator.firstWeek()).isNull()

        env.time.set(MONDAY.plusDays(2).atTime(12, 0))
        env.maintenance.recordActivity()

        assertThat(calculator.firstWeek()).isEqualTo(MONDAY)
    }

    @Test
    fun `the median of an even count is the mean of the middle pair`() {
        assertThat(MetricsCalculator.median(emptyList())).isNull()
        assertThat(MetricsCalculator.median(listOf(3, 0, 1))).isEqualTo(1.0)
        assertThat(MetricsCalculator.median(listOf(4, 0, 1, 2))).isEqualTo(1.5)
    }

    private suspend fun session(kind: ReviewKind, length: Duration) {
        val id = env.review.startSession(kind)
        env.time.advance(length)
        env.review.endSession(id)
    }

    /** Stands for R4 archiving a task the user skipped in relevance checks again and again. */
    private fun archiveByTtl(taskId: String) {
        env.db.openHelper.writableDatabase.execSQL(
            "UPDATE task SET status = 'ARCHIVED', archive_reason = 'TTL_SKIPS', archived_at = ? WHERE id = ?",
            arrayOf<Any>(env.time.now().toEpochMilli(), taskId),
        )
    }

    private companion object {
        val MONDAY: LocalDate = LocalDate.of(2026, 10, 5)
        const val MAX_ENTRIES = 64
        const val KEY = "[a-z][a-z0-9_.]{0,63}"
    }
}
