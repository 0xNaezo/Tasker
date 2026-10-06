package app.tasker.core.scheduling

import app.tasker.core.domain.notify.NotificationType
import app.tasker.core.model.Bucket
import app.tasker.core.model.Estimate
import app.tasker.core.notifications.GateResult
import app.tasker.core.scheduling.plan.MorningPlanResult
import app.tasker.core.scheduling.plan.MorningPlanSkip
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The morning ritual (PLN-4, TTL-5): catch-up, draft, then one notification with the numbers of the plan screen. */
@RunWith(RobolectricTestRunner::class)
class MorningPlanTest {
    private val env = SchedulingEnv(start = LocalDateTime.parse("2026-10-06T08:30"))
    private val morningPlan = env.morningPlan

    @After
    fun tearDown() = env.close()

    @Test
    fun `the notification has the numbers of the plan screen`() = runTest {
        env.addTask("Write the report", bucket = Bucket.TODAY, estimate = Estimate.M)
        env.addTask("Call the bank", bucket = Bucket.TODAY)

        assertThat(morningPlan.run()).isEqualTo(MorningPlanResult.Submitted(GateResult.Sent))

        val shown = env.poster.shown.single()
        assertThat(shown.type).isEqualTo(NotificationType.PLAN_READY)
        assertThat(shown.key).isEqualTo("plan|2026-10-06")
        // Working hours 09:00–18:00 minus the 30% buffer: 6 h 18 min.
        assertThat(shown.text).isEqualTo("Plan is ready: 2 tasks, 2 h of 6 h 18 min free")
        val draft = env.plans.prepare()
        assertThat(draft.plan.activeItems.sumOf { it.minutes }).isEqualTo(120)
        assertThat(draft.capacity.capacityMin).isEqualTo(6 * 60 + 18)
        assertThat(env.state.lastMorningPlanDay).isEqualTo(date("2026-10-06"))

        // A second run on the same day sends nothing new.
        assertThat(morningPlan.run()).isEqualTo(MorningPlanResult.Submitted(GateResult.Duplicate))
        assertThat(env.poster.shown).hasSize(1)
    }

    @Test
    fun `tasks waiting for review are counted (TTL-5)`() = runTest {
        // Inbox TTL is 7 days: after 8 untouched days the catch-up sends the task to review.
        env.addTask("An old idea")
        env.setNow("2026-10-14T08:30")
        env.addTask("Write the report", bucket = Bucket.TODAY)

        assertThat(morningPlan.run()).isEqualTo(MorningPlanResult.Submitted(GateResult.Sent))
        assertThat(env.poster.shown.single().text).isEqualTo("Plan is ready: 1 task, 1 h of 6 h 18 min free, and 1 task to review")
    }

    @Test
    fun `an accepted plan needs no notification`() = runTest {
        env.addTask("Write the report", bucket = Bucket.TODAY)
        env.plans.accept()

        assertThat(morningPlan.run()).isEqualTo(MorningPlanResult.Skipped(MorningPlanSkip.ALREADY_ACCEPTED))
        assertThat(env.poster.shown).isEmpty()
    }

    @Test
    fun `no notification with nothing to plan, on days off, after work or when switched off`() = runTest {
        assertThat(morningPlan.run()).isEqualTo(MorningPlanResult.Skipped(MorningPlanSkip.NOTHING_TO_PLAN))

        env.addTask("Write the report", bucket = Bucket.TODAY)
        // Saturday (interpretation 18).
        env.setNow("2026-10-10T08:30")
        assertThat(morningPlan.run()).isEqualTo(MorningPlanResult.Skipped(MorningPlanSkip.NOT_WORK_DAY))
        // Monday, but the alarm came after the working day.
        env.setNow("2026-10-12T18:30")
        assertThat(morningPlan.run()).isEqualTo(MorningPlanResult.Skipped(MorningPlanSkip.DAY_OVER))

        env.setNow("2026-10-13T08:30")
        env.store.updateData { it.copy(notifyPlan = false) }
        assertThat(morningPlan.run()).isEqualTo(MorningPlanResult.Skipped(MorningPlanSkip.DISABLED))

        assertThat(env.poster.shown).isEmpty()
        // Every run marks its day as handled, so the alarm moves on to the next working day.
        assertThat(env.state.lastMorningPlanDay).isEqualTo(date("2026-10-13"))
    }
}
