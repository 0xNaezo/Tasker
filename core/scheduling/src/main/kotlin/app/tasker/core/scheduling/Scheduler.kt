package app.tasker.core.scheduling

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.tasker.core.data.di.ApplicationScope
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AppSettings
import app.tasker.core.notifications.FlushResult
import app.tasker.core.notifications.NotificationChannels
import app.tasker.core.notifications.NotificationGate
import app.tasker.core.scheduling.maintenance.MaintenanceWorker
import app.tasker.core.scheduling.plan.MorningPlanWorker
import app.tasker.core.scheduling.reminder.ReminderPass
import app.tasker.core.scheduling.reminder.ReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Background work and alarms (tech plan §11.1, §12.2, §12.3): the daily catch-up, the morning plan, deadline reminders
 * and the end of quiet hours. Everything is inexact (WorkManager, AlarmManager windows) and idempotent, so a missed
 * or repeated run never causes an avalanche of actions. No foreground service, no exact alarms.
 *
 * Wiring in the app module:
 * - the Application implements `androidx.work.Configuration.Provider` with the Hilt worker factory, because the
 *   workers get their dependencies from Hilt:
 *   ```
 *   @HiltAndroidApp
 *   class TaskerApplication : Application(), Configuration.Provider {
 *       @Inject lateinit var workerFactory: HiltWorkerFactory
 *       override val workManagerConfiguration: Configuration
 *           get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
 *   }
 *   ```
 * - the default WorkManager initializer must not run; this module's manifest removes it and merges into the app:
 *   ```
 *   <provider
 *       android:name="androidx.startup.InitializationProvider"
 *       android:authorities="${applicationId}.androidx-startup"
 *       android:exported="false"
 *       tools:node="merge">
 *       <meta-data android:name="androidx.work.WorkManagerInitializer" android:value="androidx.startup" tools:node="remove" />
 *   </provider>
 *   ```
 * - [onAppStart] is called when the app starts (for example from `Application.onCreate`).
 */
@Singleton
class Scheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    /** The application scope; receivers of this module run their work on it. */
    @param:ApplicationScope val scope: CoroutineScope,
    private val workManager: Provider<WorkManager>,
    private val settings: SettingsRepository,
    private val clock: DayClock,
    private val alarms: Alarms,
    private val reminders: ReminderScheduler,
    private val gate: NotificationGate,
    private val state: SchedulerState,
) {
    private val started = AtomicBoolean(false)
    private val mutex = Mutex()

    /**
     * App start: notification channels and a catch-up pass in the background (tech plan §11.1); on the first call in a
     * process also every alarm, and from then on the alarms follow the settings they depend on. Cheap to call again.
     */
    fun onAppStart() {
        NotificationChannels.ensure(context)
        enqueueCatchUp()
        if (started.compareAndSet(false, true)) scope.launch(Errors) { followSettings() }
    }

    /**
     * Re-registers the daily catch-up, the morning plan alarm, the deadline reminder alarm (sending reminders that are
     * due) and the quiet-hours alarm. [realignMaintenance] moves the daily catch-up back to just after the day boundary.
     */
    suspend fun rescheduleAll(realignMaintenance: Boolean = false) = mutex.withLock {
        // Reading the settings first also moves the day clock to the day boundary setting.
        val current = settings.current()
        ensureDailyMaintenance(realignMaintenance)
        scheduleMorningPlanLocked(current)
        reminders.run()
        gate.rescheduleQuietHoursFlush()
    }

    /** Arms the morning plan alarm for the next working day (PLN-4, interpretation 18). */
    suspend fun scheduleMorningPlan() = mutex.withLock { scheduleMorningPlanLocked(settings.current()) }

    /** Boot, time or zone change, app update or a new language (tech plan §12.2). */
    suspend fun onSystemEvent(action: String) {
        if (action == Intent.ACTION_LOCALE_CHANGED) NotificationChannels.ensure(context)
        enqueueCatchUp()
        rescheduleAll(realignMaintenance = action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED)
    }

    suspend fun onReminderAlarm(): ReminderPass = reminders.run()

    suspend fun onQuietHoursEnd(): FlushResult = gate.flushQuietHoursQueue()

    /** The morning plan alarm fired: expedited work, so the plan is ready within seconds. */
    fun enqueueMorningPlan() {
        val request = OneTimeWorkRequestBuilder<MorningPlanWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.get().enqueueUniqueWork(MORNING_PLAN_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /** One catch-up pass in the background, unless one is already waiting. */
    fun enqueueCatchUp() {
        workManager.get().enqueueUniqueWork(CATCH_UP_WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<MaintenanceWorker>().build())
    }

    private fun scheduleMorningPlanLocked(current: AppSettings) {
        val at = if (current.notifyPlan) MorningPlanTiming.nextAlarm(clock.now(), current, clock, state.lastMorningPlanDay) else null
        if (at == null) {
            alarms.cancel(AlarmKind.MORNING_PLAN)
            return
        }
        // From the first alarm on, a plan time that passes while the phone is off counts as missed.
        if (state.lastMorningPlanDay == null) state.lastMorningPlanDay = clock.logicalDay(at).minusDays(1)
        alarms.set(AlarmKind.MORNING_PLAN, AlarmWindow(at, SchedulingMath.ALARM_WINDOW))
    }

    /**
     * The daily catch-up is unique periodic work whose first run lands just after the next day boundary. Existing work
     * is kept while it stays aligned; it is re-enqueued when it drifted, the boundary moved or the clock changed.
     */
    private suspend fun ensureDailyMaintenance(realign: Boolean) {
        val manager = workManager.get()
        val existing = manager.getWorkInfosForUniqueWorkFlow(DAILY_MAINTENANCE_WORK).first().firstOrNull { !it.state.isFinished }
        if (existing != null) {
            // The running catch-up re-registers the alarms itself; its next run is aligned again by a later pass.
            if (existing.state == WorkInfo.State.RUNNING) return
            val nextRun = Instant.ofEpochMilli(existing.nextScheduleTimeMillis)
            if (!realign && MaintenanceTiming.isAligned(nextRun, clock)) return
        }
        val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(Duration.ofDays(1))
            .setInitialDelay(MaintenanceTiming.initialDelay(clock.now(), clock))
            .build()
        val policy = if (existing == null) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
        manager.enqueueUniquePeriodicWork(DAILY_MAINTENANCE_WORK, policy, request)
    }

    /**
     * Registers everything for the current settings, then again whenever a setting the alarms depend on changes; a new
     * day boundary also moves the daily catch-up. A failed pass is logged and the next change tries again.
     */
    private suspend fun followSettings() {
        var boundary: Int? = null
        settings.settings.map(SchedulingInputs::of).distinctUntilChanged().collect { inputs ->
            val outcome = runCatching { rescheduleAll(realignMaintenance = boundary != null && boundary != inputs.dayBoundaryMinutes) }
            currentCoroutineContext().ensureActive()
            outcome.exceptionOrNull()?.let { Log.w(TAG, "Rescheduling failed", it) }
            boundary = inputs.dayBoundaryMinutes
        }
    }

    companion object {
        const val DAILY_MAINTENANCE_WORK = "maintenance-daily"
        const val CATCH_UP_WORK = "maintenance-catch-up"
        const val MORNING_PLAN_WORK = "morning-plan"

        private const val TAG = "Scheduler"
        private val Errors = CoroutineExceptionHandler { _, error -> Log.w(TAG, "Scheduling failed", error) }
    }
}
