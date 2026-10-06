package app.tasker.core.scheduling

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.tasker.core.scheduling.plan.MorningPlanReceiver
import app.tasker.core.scheduling.quiet.QuietHoursEndReceiver
import app.tasker.core.scheduling.reminder.DeadlineReminderReceiver
import app.tasker.core.scheduling.system.SystemEventsReceiver
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/** Inexact AlarmManager windows (tech plan §12.2): no exact alarms, one pending alarm per kind. */
@RunWith(RobolectricTestRunner::class)
class AndroidAlarmsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarms = AndroidAlarms(context)
    private val manager = shadowOf(context.getSystemService(AlarmManager::class.java))

    private val start = Instant.parse("2026-10-07T05:30:00Z")

    private fun ShadowAlarmManager.ScheduledAlarm.intent(): Intent = shadowOf(operation).savedIntent

    @Test
    fun `an alarm is a wake-up window that reaches the receiver of its kind`() {
        alarms.set(AlarmKind.MORNING_PLAN, AlarmWindow(start, Duration.ofMinutes(10)))

        val alarm = manager.scheduledAlarms.single()
        assertThat(alarm.type).isEqualTo(AlarmManager.RTC_WAKEUP)
        assertThat(alarm.triggerAtMs).isEqualTo(start.toEpochMilli())
        assertThat(alarm.windowLengthMs).isEqualTo(Duration.ofMinutes(10).toMillis())
        assertThat(alarm.isAllowWhileIdle).isFalse()
        assertThat(shadowOf(alarm.operation).isBroadcastIntent).isTrue()
        assertThat(alarm.intent().component?.className).isEqualTo(MorningPlanReceiver::class.java.name)
        assertThat(alarm.intent().action).isEqualTo(AlarmKind.MORNING_PLAN.action)
    }

    @Test
    fun `setting a kind again replaces its alarm and cancel removes only that kind`() {
        alarms.set(AlarmKind.DEADLINE_REMINDERS, AlarmWindow(start, Duration.ofMinutes(10)))
        alarms.set(AlarmKind.DEADLINE_REMINDERS, AlarmWindow(start.plusSeconds(3_600), Duration.ofMinutes(8)))
        alarms.set(AlarmKind.MORNING_PLAN, AlarmWindow(start, Duration.ofMinutes(10)))

        val reminder = manager.scheduledAlarms.single { it.intent().action == AlarmKind.DEADLINE_REMINDERS.action }
        assertThat(manager.scheduledAlarms).hasSize(2)
        assertThat(reminder.triggerAtMs).isEqualTo(start.plusSeconds(3_600).toEpochMilli())
        assertThat(reminder.windowLengthMs).isEqualTo(Duration.ofMinutes(8).toMillis())
        assertThat(reminder.intent().component?.className).isEqualTo(DeadlineReminderReceiver::class.java.name)

        alarms.cancel(AlarmKind.DEADLINE_REMINDERS)
        assertThat(manager.scheduledAlarms.single().intent().action).isEqualTo(AlarmKind.MORNING_PLAN.action)
    }

    @Test
    fun `the quiet-hours queue is delivered by a 15-minute window`() {
        val quietHoursEnd = QuietHoursEndAlarm(alarms)

        quietHoursEnd.schedule(start)

        val alarm = manager.scheduledAlarms.single()
        assertThat(alarm.triggerAtMs).isEqualTo(start.toEpochMilli())
        assertThat(alarm.windowLengthMs).isEqualTo(Duration.ofMinutes(15).toMillis())
        assertThat(alarm.intent().component?.className).isEqualTo(QuietHoursEndReceiver::class.java.name)
        quietHoursEnd.cancel()
        assertThat(manager.scheduledAlarms).isEmpty()
    }

    @Test
    fun `boot, time, zone, update and language changes reach the system events receiver`() {
        SystemEventsReceiver.HANDLED_ACTIONS.forEach { action ->
            val receivers = context.packageManager.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
            assertThat(receivers.map { it.activityInfo.name }).containsExactly(SystemEventsReceiver::class.java.name)
        }
    }
}
