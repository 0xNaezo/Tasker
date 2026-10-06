package app.tasker.core.scheduling

import app.tasker.core.domain.notify.QuietHours
import app.tasker.core.domain.notify.ReminderPlanner
import app.tasker.core.domain.notify.ReminderSlot
import app.tasker.core.model.AppSettings
import app.tasker.core.model.Deadline
import app.tasker.core.testing.TestClock
import app.tasker.core.testing.aTask
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Test

class SchedulingMathTest {
    private val clock = TestClock().clock
    private val settings = AppSettings()

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(clock.zone()).toInstant()

    private fun reminders(deadline: Deadline, offsets: List<Int> = settings.deadlineReminderMinutes): TaskReminders {
        val task = aTask(id = "t1", deadline = deadline)
        val end = deadline.instantOrNull(clock.zone()) ?: clock.at(deadline.date.plusDays(1), 0)
        return TaskReminders(task, ReminderPlanner.slots(task.id, deadline, offsets, settings, clock), end)
    }

    private val thursday18 = Deadline(date("2026-10-08"), LocalTime.of(18, 0), clock.zone())

    @Test
    fun `daily catch-up starts shortly after the next day boundary`() {
        assertThat(MaintenanceTiming.nextRun(at("2026-10-06T10:00"), clock)).isEqualTo(at("2026-10-07T04:15"))
        // At 02:00 the logical day is still Tuesday, so its boundary is a couple of hours away.
        assertThat(MaintenanceTiming.nextRun(at("2026-10-07T02:00"), clock)).isEqualTo(at("2026-10-07T04:15"))
        assertThat(MaintenanceTiming.initialDelay(at("2026-10-06T10:00"), clock)).isEqualTo(Duration.ofHours(18).plusMinutes(15))
        // The day boundary is a setting.
        clock.boundaryMinutes = 6 * 60
        assertThat(MaintenanceTiming.nextRun(at("2026-10-06T10:00"), clock)).isEqualTo(at("2026-10-07T06:15"))
    }

    @Test
    fun `catch-up follows local time over the daylight saving change`() {
        // Kyiv leaves summer time on 25 October 2026: the run is still at 04:15 local time.
        val run = MaintenanceTiming.nextRun(at("2026-10-24T10:00"), clock)
        assertThat(run).isEqualTo(at("2026-10-25T04:15"))
        assertThat(run).isEqualTo(Instant.parse("2026-10-25T02:15:00Z"))
    }

    @Test
    fun `a run is aligned when it comes soon after a boundary`() {
        assertThat(MaintenanceTiming.isAligned(at("2026-10-07T04:15"), clock)).isTrue()
        assertThat(MaintenanceTiming.isAligned(at("2026-10-07T06:00"), clock)).isTrue()
        assertThat(MaintenanceTiming.isAligned(at("2026-10-07T07:00"), clock)).isFalse()
        assertThat(MaintenanceTiming.isAligned(at("2026-10-07T03:59"), clock)).isFalse()
    }

    private fun planAlarm(now: String, lastHandled: String?, settings: AppSettings = this.settings): Instant? =
        MorningPlanTiming.nextAlarm(at(now), settings, clock, lastHandled?.let(::date))

    @Test
    fun `morning plan alarm on working days at the plan time`() {
        assertThat(planAlarm("2026-10-06T07:00", lastHandled = null)).isEqualTo(at("2026-10-06T08:30"))
        // Friday after the plan: next is Monday (interpretation 18).
        assertThat(planAlarm("2026-10-09T09:00", lastHandled = "2026-10-09")).isEqualTo(at("2026-10-12T08:30"))
        assertThat(planAlarm("2026-10-10T09:00", lastHandled = "2026-10-09")).isEqualTo(at("2026-10-12T08:30"))
        assertThat(planAlarm("2026-10-06T07:00", lastHandled = null, settings.copy(workDays = emptySet()))).isNull()
    }

    @Test
    fun `a missed morning plan still comes within the grace period, once`() {
        // The phone was off at 08:30 and boots at 09:00: the alarm is due at once.
        assertThat(planAlarm("2026-10-06T09:00", lastHandled = "2026-10-05")).isEqualTo(at("2026-10-06T08:30"))
        // Already handled today: tomorrow.
        assertThat(planAlarm("2026-10-06T09:00", lastHandled = "2026-10-06")).isEqualTo(at("2026-10-07T08:30"))
        // Too late for today's plan.
        assertThat(planAlarm("2026-10-06T11:00", lastHandled = "2026-10-05")).isEqualTo(at("2026-10-07T08:30"))
        // A fresh install at 09:00 has missed nothing: the first plan comes tomorrow.
        assertThat(planAlarm("2026-10-06T09:00", lastHandled = null)).isEqualTo(at("2026-10-07T08:30"))
    }

    @Test
    fun `the latest due reminder of a task is sent, never after the deadline (NTF-2, NTF-6)`() {
        val task = reminders(thursday18)
        assertThat(task.slots.map { it.at }).containsExactly(at("2026-10-07T18:00"), at("2026-10-08T16:00")).inOrder()

        assertThat(ReminderTiming.due(listOf(task), at("2026-10-06T10:00"), emptyMap())).isEmpty()
        assertThat(ReminderTiming.due(listOf(task), at("2026-10-07T18:05"), emptyMap()).single().slot.at).isEqualTo(at("2026-10-07T18:00"))
        // An alarm may fire a little early.
        assertThat(ReminderTiming.due(listOf(task), at("2026-10-07T17:52"), emptyMap()).single().slot.at).isEqualTo(at("2026-10-07T18:00"))
        // After a long sleep only the latest slot is due.
        assertThat(ReminderTiming.due(listOf(task), at("2026-10-08T17:00"), emptyMap()).single().slot.at).isEqualTo(at("2026-10-08T16:00"))
        assertThat(ReminderTiming.due(listOf(task), at("2026-10-08T18:00"), emptyMap())).isEmpty()
    }

    @Test
    fun `slots that were past when the deadline was set never fire`() {
        val task = reminders(thursday18)
        val changed = mapOf("t1" to at("2026-10-08T15:00"))
        assertThat(ReminderTiming.due(listOf(task), at("2026-10-08T15:30"), changed)).isEmpty()
        assertThat(ReminderTiming.due(listOf(task), at("2026-10-08T16:05"), changed).single().slot.at).isEqualTo(at("2026-10-08T16:00"))
    }

    @Test
    fun `one alarm for the earliest coming reminder within 48 hours`() {
        val soon = reminders(thursday18)
        val nextWeek = reminders(Deadline(date("2026-10-16"), LocalTime.of(12, 0), clock.zone()))
        assertThat(ReminderTiming.next(listOf(nextWeek, soon), at("2026-10-06T10:00"))?.slot?.at).isEqualTo(at("2026-10-07T18:00"))
        assertThat(ReminderTiming.next(listOf(nextWeek, soon), at("2026-10-07T18:05"))?.slot?.at).isEqualTo(at("2026-10-08T16:00"))
        assertThat(ReminderTiming.next(listOf(nextWeek), at("2026-10-06T10:00"))).isNull()
        // A slot due within the alarm window is sent now rather than armed.
        assertThat(ReminderTiming.next(listOf(soon), at("2026-10-07T17:55"))?.slot?.at).isEqualTo(at("2026-10-08T16:00"))
    }

    @Test
    fun `date-only deadline is reminded the day before at the plan time`() {
        val task = reminders(Deadline(date("2026-10-09")))
        assertThat(task.slots.map { it.at }).containsExactly(at("2026-10-08T08:30"))
        assertThat(task.deadlineEnd).isEqualTo(at("2026-10-10T00:00"))
    }

    @Test
    fun `reminder windows close before quiet hours and the deadline`() {
        val quiet = QuietHours.of(settings)
        val now = at("2026-10-06T10:00")
        val far = at("2026-10-10T00:00")

        assertThat(ReminderTiming.window(at("2026-10-07T18:00"), far, quiet, clock, now))
            .isEqualTo(AlarmWindow(at("2026-10-07T18:00"), Duration.ofMinutes(10)))
        assertThat(ReminderTiming.window(at("2026-10-07T21:55"), far, quiet, clock, now))
            .isEqualTo(AlarmWindow(at("2026-10-07T21:50"), Duration.ofMinutes(10)))
        assertThat(ReminderTiming.window(at("2026-10-07T17:55"), at("2026-10-07T18:00"), quiet, clock, now))
            .isEqualTo(AlarmWindow(at("2026-10-07T17:50"), Duration.ofMinutes(10)))
        assertThat(ReminderTiming.window(at("2026-10-06T21:55"), far, quiet, clock, at("2026-10-06T21:52")))
            .isEqualTo(AlarmWindow(at("2026-10-06T21:52"), Duration.ofMinutes(8)))
        // A slot inside quiet hours keeps its normal window; the gate defers it.
        assertThat(ReminderTiming.window(at("2026-10-07T23:00"), far, quiet, clock, now))
            .isEqualTo(AlarmWindow(at("2026-10-07T23:00"), Duration.ofMinutes(10)))
        assertThat(ReminderTiming.window(at("2026-10-07T21:55"), far, QuietHours(0, 0), clock, now))
            .isEqualTo(AlarmWindow(at("2026-10-07T21:55"), Duration.ofMinutes(10)))
    }

    @Test
    fun `reminder slot adjusted before quiet hours fires before them (interpretation 17)`() {
        val early = Deadline(date("2026-10-08"), LocalTime.of(7, 0), clock.zone())
        val task = reminders(early, listOf(120))
        val slot: ReminderSlot = task.slots.single()
        assertThat(slot.at).isEqualTo(at("2026-10-07T21:59"))
        assertThat(ReminderTiming.window(slot.at, task.deadlineEnd, QuietHours.of(settings), clock, at("2026-10-07T12:00")))
            .isEqualTo(AlarmWindow(at("2026-10-07T21:50"), Duration.ofMinutes(10)))
    }

    @Test
    fun `a reminder held back by quiet hours is shown when they end, unless the deadline comes first`() {
        val quiet = QuietHours.of(settings)
        val deadlineEnd = at("2026-10-08T23:30")
        assertThat(ReminderTiming.deliveryTime(at("2026-10-07T23:30"), deadlineEnd, quiet, clock)).isEqualTo(at("2026-10-08T08:00"))
        assertThat(ReminderTiming.deliveryTime(at("2026-10-07T18:00"), deadlineEnd, quiet, clock)).isEqualTo(at("2026-10-07T18:00"))
        // Waiting would pass the deadline: the gate sends at once.
        assertThat(ReminderTiming.deliveryTime(at("2026-10-07T23:30"), at("2026-10-08T07:00"), quiet, clock))
            .isEqualTo(at("2026-10-07T23:30"))
    }

    @Test
    fun `only settings that move alarms count as scheduling inputs`() {
        val base = SchedulingInputs.of(settings)
        assertThat(SchedulingInputs.of(settings.copy(bufferPercent = 10, wipLimit = 5))).isEqualTo(base)
        assertThat(SchedulingInputs.of(settings.copy(workStartMinutes = 10 * 60))).isNotEqualTo(base)
        assertThat(SchedulingInputs.of(settings.copy(quietEndMinutes = 9 * 60))).isNotEqualTo(base)
        assertThat(SchedulingInputs.of(settings.copy(dayBoundaryMinutes = 5 * 60))).isNotEqualTo(base)
    }
}
