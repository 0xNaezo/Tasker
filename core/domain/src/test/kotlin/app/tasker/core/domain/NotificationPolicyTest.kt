package app.tasker.core.domain

import app.tasker.core.domain.notify.GateDecision
import app.tasker.core.domain.notify.NotificationPolicy
import app.tasker.core.domain.notify.NotificationType
import app.tasker.core.domain.notify.QuietHours
import app.tasker.core.domain.notify.ReminderPlanner
import app.tasker.core.model.AppSettings
import app.tasker.core.model.Deadline
import app.tasker.core.testing.TestClock
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Test

class NotificationPolicyTest {
    private val clock = TestClock().clock
    private val settings = AppSettings()

    private fun at(text: String) = clock.at(
        LocalDateTime.parse(text).toLocalDate(),
        LocalDateTime.parse(text).toLocalTime().toSecondOfDay() / 60,
    )

    @Test
    fun `there is no "you did not do it" notification type (NTF-6)`() {
        assertThat(NotificationType.entries.map { it.name })
            .containsExactly("PLAN_READY", "DEADLINE", "WEEKLY_REVIEW", "INTEGRATION", "QUIET_HOURS_DIGEST")
    }

    @Test
    fun `quiet hours wrap over midnight`() {
        val quiet = QuietHours(22 * 60, 8 * 60)
        assertThat(quiet.contains(23 * 60)).isTrue()
        assertThat(quiet.contains(7 * 60 + 59)).isTrue()
        assertThat(quiet.contains(8 * 60)).isFalse()
        assertThat(quiet.contains(21 * 60 + 59)).isFalse()
        assertThat(quiet.endAfter(at("2026-10-06T23:00"), clock)).isEqualTo(at("2026-10-07T08:00"))
        assertThat(quiet.endAfter(at("2026-10-07T03:00"), clock)).isEqualTo(at("2026-10-07T08:00"))
    }

    @Test
    fun `gate defers in quiet hours except the morning plan, and enforces the budget`() {
        val night = at("2026-10-06T23:00")
        assertThat(NotificationPolicy.decide(NotificationType.WEEKLY_REVIEW, night, settings, 0, clock))
            .isEqualTo(GateDecision.Defer(at("2026-10-07T08:00")))
        assertThat(NotificationPolicy.decide(NotificationType.PLAN_READY, at("2026-10-07T07:30"), settings, 0, clock))
            .isEqualTo(GateDecision.Send)
        assertThat(NotificationPolicy.decide(NotificationType.PLAN_READY, at("2026-10-07T12:00"), settings, 3, clock))
            .isEqualTo(GateDecision.OverBudget)
        assertThat(NotificationPolicy.decide(NotificationType.DEADLINE, at("2026-10-07T12:00"), settings, 3, clock))
            .isEqualTo(GateDecision.Send)
        assertThat(
            NotificationPolicy.decide(NotificationType.DEADLINE, at("2026-10-07T12:00"), settings.copy(notifyDeadlines = false), 0, clock),
        )
            .isEqualTo(GateDecision.Disabled)
    }

    @Test
    fun `date-only deadline is reminded the day before at plan time`() {
        val slots = ReminderPlanner.slots("t", Deadline(date("2026-10-09")), settings.deadlineReminderMinutes, settings, clock)
        assertThat(slots.map { it.at }).containsExactly(at("2026-10-08T08:30"))
    }

    @Test
    fun `timed deadline is reminded a day and two hours before`() {
        val deadline = Deadline(date("2026-10-09"), LocalTime.of(18, 0), clock.zone())
        val slots = ReminderPlanner.slots("t", deadline, settings.deadlineReminderMinutes, settings, clock)
        assertThat(slots.map { it.at }).containsExactly(at("2026-10-08T18:00"), at("2026-10-09T16:00")).inOrder()
    }

    @Test
    fun `reminder that would arrive after the deadline moves before quiet hours`() {
        val deadline = Deadline(date("2026-10-09"), LocalTime.of(7, 0), clock.zone())
        val slots = ReminderPlanner.slots("t", deadline, listOf(120), settings, clock)
        assertThat(slots.single().at).isEqualTo(at("2026-10-08T21:59"))
    }

    @Test
    fun `morning plan only on working days`() {
        val friday = at("2026-10-09T09:00")
        assertThat(ReminderPlanner.nextPlanTime(friday, settings, clock)).isEqualTo(at("2026-10-12T08:30"))
    }
}
