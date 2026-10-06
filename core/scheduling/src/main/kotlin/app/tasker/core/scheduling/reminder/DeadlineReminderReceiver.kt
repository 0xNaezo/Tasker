package app.tasker.core.scheduling.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.tasker.core.notifications.launchAsync
import app.tasker.core.scheduling.AlarmKind
import app.tasker.core.scheduling.Scheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Deadline reminder alarm (NTF-2): sends what is due and arms the next reminder. */
@AndroidEntryPoint
class DeadlineReminderReceiver : BroadcastReceiver() {
    @Inject
    lateinit var scheduler: Scheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmKind.DEADLINE_REMINDERS.action) return
        launchAsync(scheduler.scope) { scheduler.onReminderAlarm() }
    }
}
