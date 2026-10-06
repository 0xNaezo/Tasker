package app.tasker.core.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.tasker.core.notifications.QuietHoursAlarm
import app.tasker.core.scheduling.plan.MorningPlanReceiver
import app.tasker.core.scheduling.quiet.QuietHoursEndReceiver
import app.tasker.core.scheduling.reminder.DeadlineReminderReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** The app keeps at most one pending alarm of each kind (tech plan §12.2). */
enum class AlarmKind(val requestCode: Int, val action: String) {
    MORNING_PLAN(1, "app.tasker.scheduling.action.MORNING_PLAN"),
    DEADLINE_REMINDERS(2, "app.tasker.scheduling.action.DEADLINE_REMINDERS"),
    QUIET_HOURS_END(3, "app.tasker.scheduling.action.QUIET_HOURS_END"),
}

/** Inexact alarms; setting a kind again replaces its pending alarm. Tests use a fake. */
interface Alarms {
    fun set(kind: AlarmKind, window: AlarmWindow)

    fun cancel(kind: AlarmKind)
}

/**
 * [Alarms] on AlarmManager windows: no exact alarms and no SCHEDULE_EXACT_ALARM permission (tech plan §5, §12.2).
 * RTC_WAKEUP follows wall-clock time, so a time or zone change only needs a re-registration, which the system events
 * receiver does. In deep Doze a window alarm waits for the next maintenance window or for the user to wake the phone.
 */
@Singleton
class AndroidAlarms @Inject constructor(@param:ApplicationContext private val context: Context) : Alarms {
    private val manager: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    override fun set(kind: AlarmKind, window: AlarmWindow) {
        manager.setWindow(AlarmManager.RTC_WAKEUP, window.start.toEpochMilli(), window.length.toMillis(), pendingIntent(kind))
    }

    override fun cancel(kind: AlarmKind) {
        manager.cancel(pendingIntent(kind))
    }

    private fun pendingIntent(kind: AlarmKind): PendingIntent {
        val receiver = when (kind) {
            AlarmKind.MORNING_PLAN -> MorningPlanReceiver::class.java
            AlarmKind.DEADLINE_REMINDERS -> DeadlineReminderReceiver::class.java
            AlarmKind.QUIET_HOURS_END -> QuietHoursEndReceiver::class.java
        }
        val intent = Intent(context, receiver).setAction(kind.action)
        return PendingIntent.getBroadcast(
            context,
            kind.requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Delivery of the quiet-hours queue for the notification gate (NTF-4): an alarm at the end of quiet hours. */
@Singleton
class QuietHoursEndAlarm @Inject constructor(private val alarms: Alarms) : QuietHoursAlarm {
    override fun schedule(at: Instant) = alarms.set(AlarmKind.QUIET_HOURS_END, AlarmWindow(at, SchedulingMath.QUIET_HOURS_WINDOW))

    override fun cancel() = alarms.cancel(AlarmKind.QUIET_HOURS_END)
}
