package app.tasker.core.notifications

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationManagerCompat.IMPORTANCE_DEFAULT
import androidx.core.app.NotificationManagerCompat.IMPORTANCE_LOW
import app.tasker.core.domain.notify.NotificationType

/**
 * Notification channels (NTF-1, tech plan §12.1). Weekly review and integrations belong to the next version: their
 * channels exist so the user can tune them in advance, but nothing posts there yet; integrations are quiet.
 */
object NotificationChannels {
    const val PLAN = "plan"
    const val DEADLINES = "deadlines"
    const val WEEKLY_REVIEW = "weekly_review"
    const val INTEGRATIONS = "integrations"

    private class Spec(val id: String, val importance: Int, @param:StringRes val name: Int, @param:StringRes val description: Int)

    private val specs = listOf(
        Spec(PLAN, IMPORTANCE_DEFAULT, R.string.notification_channel_plan, R.string.notification_channel_plan_description),
        Spec(DEADLINES, IMPORTANCE_DEFAULT, R.string.notification_channel_deadlines, R.string.notification_channel_deadlines_description),
        Spec(
            WEEKLY_REVIEW,
            IMPORTANCE_DEFAULT,
            R.string.notification_channel_weekly_review,
            R.string.notification_channel_weekly_review_description,
        ),
        Spec(
            INTEGRATIONS,
            IMPORTANCE_LOW,
            R.string.notification_channel_integrations,
            R.string.notification_channel_integrations_description,
        ),
    )

    fun forType(type: NotificationType): String = when (type) {
        NotificationType.PLAN_READY -> PLAN
        // The quiet-hours group holds what was deferred, which in practice are deadline reminders.
        NotificationType.DEADLINE, NotificationType.QUIET_HOURS_DIGEST -> DEADLINES
        NotificationType.WEEKLY_REVIEW -> WEEKLY_REVIEW
        NotificationType.INTEGRATION -> INTEGRATIONS
    }

    /**
     * Creates the channels or updates their names and descriptions to the current language. Idempotent: the importance
     * and anything else the user changed stay as they are.
     */
    fun ensure(context: Context) {
        val channels = specs.map { spec ->
            NotificationChannelCompat.Builder(spec.id, spec.importance)
                .setName(context.getString(spec.name))
                .setDescription(context.getString(spec.description))
                .build()
        }
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(channels)
    }
}
