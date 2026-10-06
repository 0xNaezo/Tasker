package app.tasker.core.scheduling.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.tasker.core.notifications.launchAsync
import app.tasker.core.scheduling.Scheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Boot, time and zone changes, app update and language change (tech plan §11.1, §12.2): alarms do not survive a
 * reboot and wall-clock alarms move with the clock, so everything is re-registered and a catch-up pass is enqueued.
 * Not exported: these broadcasts come from the system, which may deliver to non-exported receivers.
 */
@AndroidEntryPoint
class SystemEventsReceiver : BroadcastReceiver() {
    @Inject
    lateinit var scheduler: Scheduler

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return
        launchAsync(scheduler.scope) { scheduler.onSystemEvent(action) }
    }

    companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCALE_CHANGED,
        )
    }
}
