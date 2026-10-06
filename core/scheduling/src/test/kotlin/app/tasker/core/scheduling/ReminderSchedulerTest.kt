package app.tasker.core.scheduling

import app.tasker.core.data.command.FieldUpdate
import app.tasker.core.data.command.TaskEdit
import app.tasker.core.model.AppSettings
import app.tasker.core.model.Deadline
import app.tasker.core.model.ReminderOffsets
import app.tasker.core.notifications.FlushResult
import app.tasker.core.notifications.GateResult
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Deadline reminders end to end: commits, the reminder alarm, the gate and the quiet-hours queue (NTF-2…NTF-4). */
@RunWith(RobolectricTestRunner::class)
class ReminderSchedulerTest {
    private var env = SchedulingEnv()

    @After
    fun tearDown() = env.close()

    private fun use(newEnv: SchedulingEnv) {
        env.close()
        env = newEnv
    }

    private fun deadline(date: String, time: String? = null): Deadline =
        if (time == null) Deadline(date(date)) else Deadline(date(date), LocalTime.parse(time), env.clock.zone())

    private val reminderAlarm: AlarmWindow? get() = env.alarms.windows[AlarmKind.DEADLINE_REMINDERS]

    private fun window(start: String, minutes: Long = 10) = AlarmWindow(env.at(start), Duration.ofMinutes(minutes))

    @Test
    fun `a new deadline arms one alarm for its first reminder and sends nothing yet`() = runTest {
        env.addTask("Send the report", deadline("2026-10-08", "18:00"))

        assertThat(env.poster.shown).isEmpty()
        assertThat(reminderAlarm).isEqualTo(window("2026-10-07T18:00"))
    }

    @Test
    fun `reminders come 24 h and 2 h before, each once, and never after the deadline (NTF-2, NTF-6)`() = runTest {
        env.addTask("Send the report", deadline("2026-10-08", "18:00"))

        env.setNow("2026-10-07T18:03")
        assertThat(env.reminders.run().results).containsExactly(GateResult.Sent)
        val first = env.poster.shown.single()
        assertThat(first.title).isEqualTo("Send the report")
        assertThat(first.text).isEqualTo("Deadline tomorrow at 18:00")
        assertThat(reminderAlarm).isEqualTo(window("2026-10-08T16:00"))

        // Another pass (boot, daily catch-up) does not repeat it.
        env.setNow("2026-10-07T19:00")
        assertThat(env.reminders.run().results).containsExactly(GateResult.Duplicate)

        env.setNow("2026-10-08T16:01")
        assertThat(env.reminders.run().results).containsExactly(GateResult.Sent)
        assertThat(env.poster.shown.last().text).isEqualTo("Deadline today at 18:00")
        assertThat(reminderAlarm).isNull()

        env.setNow("2026-10-08T18:30")
        assertThat(env.reminders.run().results).isEmpty()
        assertThat(env.poster.shown).hasSize(2)
    }

    @Test
    fun `after a long sleep only the latest missed reminder comes, once`() = runTest {
        env.addTask("Send the report", deadline("2026-10-08", "18:00"))

        // The phone was off from Wednesday morning until Thursday 17:00.
        env.setNow("2026-10-08T17:00")
        assertThat(env.reminders.run().results).containsExactly(GateResult.Sent)
        assertThat(env.reminders.run().results).containsExactly(GateResult.Duplicate)
        assertThat(env.poster.shown.single().text).isEqualTo("Deadline today at 18:00")
    }

    @Test
    fun `a deadline set inside its reminder window does not fire the reminders already past`() = runTest {
        val task = env.addTask("Call the bank", deadline("2026-10-09", "18:00"))
        assertThat(reminderAlarm).isNull()

        // Tuesday 14:00: the deadline moves to tomorrow 13:00, so "24 h before" (today 13:00) has passed.
        env.setNow("2026-10-06T14:00")
        env.commit { env.commands.edit(task.id, TaskEdit(deadline = FieldUpdate.Set(deadline("2026-10-07", "13:00")))) }

        assertThat(env.poster.shown).isEmpty()
        assertThat(reminderAlarm).isEqualTo(window("2026-10-07T11:00"))
        env.setNow("2026-10-07T11:02")
        assertThat(env.reminders.run().results).containsExactly(GateResult.Sent)
        assertThat(env.poster.shown.single().text).isEqualTo("Deadline today at 13:00")
    }

    @Test
    fun `reminder offsets come from the task or from the settings`() = runTest {
        use(SchedulingEnv(settings = AppSettings(deadlineReminderMinutes = listOf(60))))
        val global = env.addTask("Global", deadline("2026-10-08", "18:00"))
        val own = env.addTask("Own", deadline("2026-10-08", "18:00"), reminders = ReminderOffsets(listOf(30)))
        // Both reminders are more than 48 hours away: the daily run arms them later.
        assertThat(reminderAlarm).isNull()

        env.setNow("2026-10-07T12:00")
        env.reminders.run()
        assertThat(reminderAlarm).isEqualTo(window("2026-10-08T17:00"))

        env.setNow("2026-10-08T17:02")
        env.reminders.run()
        assertThat(reminderAlarm).isEqualTo(window("2026-10-08T17:30"))
        env.setNow("2026-10-08T17:31")
        env.reminders.run()

        assertThat(env.poster.shown.map { it.taskId }).containsExactly(global.id, own.id).inOrder()
        assertThat(reminderAlarm).isNull()
    }

    @Test
    fun `finishing or deleting a task withdraws its reminders`() = runTest {
        val done = env.addTask("Done", deadline("2026-10-08", "18:00"))
        val deleted = env.addTask("Deleted", deadline("2026-10-08", "19:00"))
        env.setNow("2026-10-07T19:05")
        env.reminders.run()
        assertThat(env.poster.shown).hasSize(2)

        env.commit { env.commands.complete(done.id) }
        env.commit { env.commands.deletePermanently(deleted.id) }

        assertThat(env.poster.cancelled).containsExactly(done.id, deleted.id).inOrder()
        assertThat(reminderAlarm).isNull()
        assertThat(env.state.reminderChanges()).isEmpty()
    }

    @Test
    fun `switching deadline reminders off cancels the alarm`() = runTest {
        env.addTask("Send the report", deadline("2026-10-08", "18:00"))
        assertThat(reminderAlarm).isNotNull()

        env.store.updateData { it.copy(notifyDeadlines = false) }
        env.setNow("2026-10-07T18:03")

        assertThat(env.reminders.run().nextAlarmAt).isNull()
        assertThat(reminderAlarm).isNull()
        assertThat(env.poster.shown).isEmpty()
    }

    @Test
    fun `a reminder in quiet hours waits for the morning and is worded for it (NTF-4)`() = runTest {
        env.addTask("Pay the rent", deadline("2026-10-08", "23:30"))
        assertThat(reminderAlarm).isEqualTo(window("2026-10-07T23:30"))

        env.setNow("2026-10-07T23:31")
        assertThat(env.reminders.run().results).containsExactly(GateResult.Deferred(env.at("2026-10-08T08:00")))
        assertThat(env.alarms.windows[AlarmKind.QUIET_HOURS_END]).isEqualTo(window("2026-10-08T08:00", minutes = 15))
        // The 2-hour reminder must come before quiet hours begin at 22:00.
        assertThat(reminderAlarm).isEqualTo(window("2026-10-08T21:30"))
        // The daily catch-up at 04:15 does not queue it twice.
        env.setNow("2026-10-08T04:15")
        env.reminders.run()

        env.setNow("2026-10-08T08:04")
        assertThat(env.gate.flushQuietHoursQueue()).isEqualTo(FlushResult(delivered = 1, dropped = 0, asGroup = false))
        assertThat(env.poster.shown.single().text).isEqualTo("Deadline today at 23:30")
        assertThat(env.alarms.windows[AlarmKind.QUIET_HOURS_END]).isNull()
        assertThat(env.reminders.run().results).containsExactly(GateResult.Duplicate)
    }

    @Test
    fun `a changed deadline drops the reminder queued for the old one`() = runTest {
        val task = env.addTask("Pay the rent", deadline("2026-10-08", "23:30"))
        env.setNow("2026-10-07T23:31")
        env.reminders.run()

        env.setNow("2026-10-07T23:40")
        env.commit { env.commands.edit(task.id, TaskEdit(deadline = FieldUpdate.Set(deadline("2026-10-10")))) }

        assertThat(env.gate.nextFlushAt()).isNull()
        assertThat(env.alarms.windows[AlarmKind.QUIET_HOURS_END]).isNull()
        env.setNow("2026-10-08T08:04")
        assertThat(env.gate.flushQuietHoursQueue()).isEqualTo(FlushResult.NOTHING)
        // The new date-only deadline is reminded the day before at the plan time.
        assertThat(reminderAlarm).isEqualTo(window("2026-10-09T08:30"))
    }

    @Test
    fun `commits that do not touch deadlines leave reminders alone`() = runTest {
        use(SchedulingEnv(start = LocalDateTime.parse("2026-10-06T12:00")))
        env.addTask("No deadline")

        assertThat(reminderAlarm).isNull()
        assertThat(env.state.reminderChanges()).isEmpty()
        assertThat(env.poster.cancelled).isEmpty()
    }
}
