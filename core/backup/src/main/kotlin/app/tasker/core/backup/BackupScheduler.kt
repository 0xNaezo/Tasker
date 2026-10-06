package app.tasker.core.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.tasker.core.domain.time.DayClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules [BackupWorker] (tech plan §12.3): unique periodic work every 24 hours while the battery is not low. The
 * first run is placed at night; WorkManager keeps the period afterwards, give or take Doze.
 */
@Singleton
class BackupScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val clock: DayClock,
) {
    /** Enqueues the daily backup unless it is already scheduled; safe to call on every app start. */
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setInitialDelay(initialDelay(clock.now(), clock.zone()).toMinutes(), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "tasker-daily-backup"
        const val PERIOD_HOURS = 24L

        /** Local time of the first run: night, before the default 04:00 day boundary, so a backup closes its day. */
        val NIGHT: LocalTime = LocalTime.of(3, 0)

        /** Time from [now] to the next [at] in [zone]. */
        fun initialDelay(now: Instant, zone: ZoneId, at: LocalTime = NIGHT): Duration {
            val local = now.atZone(zone)
            val today = local.toLocalDate().atTime(at).atZone(zone)
            val next = if (today.isAfter(local)) today else local.toLocalDate().plusDays(1).atTime(at).atZone(zone)
            return Duration.between(now, next.toInstant())
        }
    }
}
