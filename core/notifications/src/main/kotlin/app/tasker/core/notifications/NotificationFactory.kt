package app.tasker.core.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import app.tasker.core.domain.notify.NotificationType
import app.tasker.core.notifications.action.NotificationActionReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds platform notifications. Notifications about a task carry its actions (NTF-5): "Done" is handled by a
 * receiver without UI, "Postpone" and a tap open the app directly through activity PendingIntents.
 */
@Singleton
class NotificationFactory @Inject constructor(@param:ApplicationContext private val context: Context) {
    /** One notification; inside the quiet-hours [group] only the summary makes a sound. */
    fun build(request: NotificationRequest, group: String? = null): Notification {
        val builder = NotificationCompat.Builder(context, NotificationChannels.forType(request.type))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(request.title)
            .setContentText(request.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(activity(openUri(request)))
        val taskId = request.taskId
        if (taskId != null) {
            builder.addAction(0, context.getString(R.string.notification_action_done), completeIntent(taskId))
            builder.addAction(0, context.getString(R.string.notification_action_postpone), activity(DeepLinks.postpone(taskId)))
        }
        if (group != null) builder.setGroup(group).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        return builder.build()
    }

    /** Summary of the group delivered after quiet hours (NTF-4): how many notifications and their titles. */
    fun groupSummary(requests: List<NotificationRequest>, group: String): Notification {
        val title = context.getString(R.string.notification_digest_title)
        val count = context.resources.getQuantityString(R.plurals.notification_digest_text, requests.size, requests.size)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title).setSummaryText(count)
        requests.forEach { style.addLine(it.title) }
        return NotificationCompat.Builder(context, NotificationChannels.forType(requests.firstOrNull()?.type ?: NotificationType.DEADLINE))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(count)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setGroup(group)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setContentIntent(activity(DeepLinks.plan()))
            .build()
    }

    /** Shown while the morning plan is prepared as expedited work, which runs as a short foreground job before Android 12. */
    fun preparingPlan(): Notification = NotificationCompat.Builder(context, NotificationChannels.PLAN)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.notification_preparing_plan))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setSilent(true)
        .build()

    private fun openUri(request: NotificationRequest): Uri {
        val taskId = request.taskId
        return when {
            taskId != null -> DeepLinks.task(taskId)
            request.type == NotificationType.WEEKLY_REVIEW -> DeepLinks.review()
            else -> DeepLinks.plan()
        }
    }

    private fun activity(uri: Uri): PendingIntent =
        PendingIntent.getActivity(context, 0, DeepLinks.viewIntent(context, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS)

    private fun completeIntent(taskId: String): PendingIntent =
        PendingIntent.getBroadcast(context, 0, NotificationActionReceiver.completeIntent(context, taskId), FLAGS)

    private companion object {
        // Intents differ by their data URI, so one request code is enough.
        const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
