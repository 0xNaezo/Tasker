package app.tasker.core.notifications.action

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.tasker.core.notifications.DeepLink
import app.tasker.core.notifications.DeepLinks
import app.tasker.core.notifications.launchAsync
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * "Done" from a task notification, handled without opening the app (NTF-5). Only explicit intents from the app's own
 * PendingIntents reach it (not exported). "Postpone" and "Open" start activities directly instead: Android 12+ does
 * not let a receiver started from a notification open an activity.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var actions: NotificationActions

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMPLETE) return
        val taskId = (DeepLinks.parse(intent.data) as? DeepLink.Task)?.taskId ?: return
        launchAsync(actions.scope) { actions.complete(taskId) }
    }

    companion object {
        const val ACTION_COMPLETE = "app.tasker.notifications.action.COMPLETE"

        /** The task id travels in the data URI, so every task gets its own PendingIntent. */
        fun completeIntent(context: Context, taskId: String): Intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(ACTION_COMPLETE)
            .setData(DeepLinks.task(taskId))
    }
}
