package app.tasker.core.notifications.di

import app.tasker.core.notifications.NotificationPoster
import app.tasker.core.notifications.QuietHoursAlarm
import app.tasker.core.notifications.SystemNotificationPoster
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationsModule {
    @Binds
    abstract fun poster(poster: SystemNotificationPoster): NotificationPoster

    /** Bound by `core:scheduling`; without it queued notifications are delivered on the next flush call only. */
    @BindsOptionalOf
    abstract fun quietHoursAlarm(): QuietHoursAlarm
}
