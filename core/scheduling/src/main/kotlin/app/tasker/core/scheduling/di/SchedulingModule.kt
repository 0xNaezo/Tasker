package app.tasker.core.scheduling.di

import android.content.Context
import androidx.work.WorkManager
import app.tasker.core.data.effects.CommitListener
import app.tasker.core.notifications.QuietHoursAlarm
import app.tasker.core.scheduling.Alarms
import app.tasker.core.scheduling.AndroidAlarms
import app.tasker.core.scheduling.QuietHoursEndAlarm
import app.tasker.core.scheduling.reminder.ReminderCommitListener
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class SchedulingModule {
    @Binds
    abstract fun alarms(alarms: AndroidAlarms): Alarms

    /** Fills the optional alarm of the notification gate's quiet-hours queue. */
    @Binds
    abstract fun quietHoursAlarm(alarm: QuietHoursEndAlarm): QuietHoursAlarm

    /** Reschedules deadline reminders after commits (post-commit effect, tech plan §4.3). */
    @Binds
    @IntoSet
    abstract fun reminderListener(listener: ReminderCommitListener): CommitListener

    companion object {
        /** Resolved lazily (through `Provider`) so WorkManager initializes on demand with the app's configuration. */
        @Provides
        fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
    }
}
