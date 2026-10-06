package app.tasker.core.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.tasker.core.domain.notify.NotificationType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Tag and id of a shown notification; a new notification with the same pair replaces the old one. */
internal data class NotificationId(val tag: String, val id: Int)

internal object NotificationIds {
    private const val PLAN_ID = 1
    private const val TASK_ID = 2
    private const val OTHER_ID = 3
    private const val SUMMARY_ID = 4

    const val QUIET_HOURS_GROUP = "app.tasker.notifications.QUIET_HOURS"
    val QUIET_HOURS_SUMMARY = NotificationId("quiet_hours", SUMMARY_ID)

    /** One notification per task: the 2-hour reminder replaces the one from the day before. */
    fun task(taskId: String) = NotificationId("task:$taskId", TASK_ID)

    fun of(request: NotificationRequest): NotificationId {
        val taskId = request.taskId
        return when {
            taskId != null -> task(taskId)
            request.type == NotificationType.PLAN_READY -> NotificationId("plan", PLAN_ID)
            else -> NotificationId("key:${request.key}", OTHER_ID)
        }
    }
}

/** [NotificationPoster] on top of NotificationManagerCompat. */
@Singleton
class SystemNotificationPoster @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val factory: NotificationFactory,
) : NotificationPoster {
    private val manager = NotificationManagerCompat.from(context)

    @Volatile
    private var channelsReady = false

    override fun canPost(): Boolean = checkPostPermission() && manager.areNotificationsEnabled()

    override fun isChannelEnabled(type: NotificationType): Boolean {
        ensureChannels()
        val channel = manager.getNotificationChannelCompat(NotificationChannels.forType(type)) ?: return false
        return channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    override fun show(request: NotificationRequest) {
        if (!checkPostPermission()) return
        ensureChannels()
        val id = NotificationIds.of(request)
        manager.notify(id.tag, id.id, factory.build(request))
    }

    override fun showGroup(requests: List<NotificationRequest>) {
        if (requests.isEmpty() || !checkPostPermission()) return
        ensureChannels()
        for (request in requests) {
            val id = NotificationIds.of(request)
            manager.notify(id.tag, id.id, factory.build(request, NotificationIds.QUIET_HOURS_GROUP))
        }
        val summary = NotificationIds.QUIET_HOURS_SUMMARY
        manager.notify(summary.tag, summary.id, factory.groupSummary(requests, NotificationIds.QUIET_HOURS_GROUP))
    }

    override fun cancelTask(taskId: String) {
        val id = NotificationIds.task(taskId)
        manager.cancel(id.tag, id.id)
    }

    /** Posting is a runtime permission from Android 13; before that only the user's notification switch counts. */
    private fun checkPostPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannels() {
        if (channelsReady) return
        NotificationChannels.ensure(context)
        channelsReady = true
    }
}
