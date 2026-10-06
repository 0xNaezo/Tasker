package app.tasker.core.notifications

import app.tasker.core.domain.notify.NotificationType
import java.time.Instant

/** The platform side of the gate: whether notifications may be shown, and showing them. Tests use a fake. */
interface NotificationPoster {
    /** Notifications are on for the app and, on Android 13+, POST_NOTIFICATIONS is granted. */
    fun canPost(): Boolean

    /** The user did not block the channel of [type] in the system settings. */
    fun isChannelEnabled(type: NotificationType): Boolean

    fun show(request: NotificationRequest)

    /** One group: a summary and a child per request, every child with its own actions (NTF-4, NTF-5). */
    fun showGroup(requests: List<NotificationRequest>)

    /** Removes the notification about a task, e.g. after "Done". */
    fun cancelTask(taskId: String)
}

/** Wakes the app when the quiet-hours queue becomes due; implemented by `core:scheduling` with an inexact alarm. */
interface QuietHoursAlarm {
    fun schedule(at: Instant)

    fun cancel()
}
