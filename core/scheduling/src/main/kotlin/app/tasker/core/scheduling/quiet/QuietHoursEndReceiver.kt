package app.tasker.core.scheduling.quiet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.tasker.core.notifications.launchAsync
import app.tasker.core.scheduling.AlarmKind
import app.tasker.core.scheduling.Scheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** End of quiet hours (NTF-4): delivers the queued notifications as one group. */
@AndroidEntryPoint
class QuietHoursEndReceiver : BroadcastReceiver() {
    @Inject
    lateinit var scheduler: Scheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmKind.QUIET_HOURS_END.action) return
        launchAsync(scheduler.scope) { scheduler.onQuietHoursEnd() }
    }
}
