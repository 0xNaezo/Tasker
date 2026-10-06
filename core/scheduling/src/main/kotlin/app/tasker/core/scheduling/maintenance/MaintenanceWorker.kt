package app.tasker.core.scheduling.maintenance

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.tasker.core.data.maintenance.MaintenanceRunner
import app.tasker.core.scheduling.Scheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Catch-up of rules R1–R9 (tech plan §11.1, §12.3): daily after the day boundary and once on app start, boot and time
 * changes. The pass is idempotent, so extra runs are harmless; afterwards the alarms are re-registered, which keeps
 * the 48-hour reminder horizon moving.
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val maintenance: MaintenanceRunner,
    private val scheduler: Scheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val outcome = runCatching {
            maintenance.catchUp()
            scheduler.rescheduleAll()
        }
        currentCoroutineContext().ensureActive()
        val error = outcome.exceptionOrNull() ?: return Result.success()
        Log.w(TAG, "Catch-up failed", error)
        return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    private companion object {
        const val TAG = "MaintenanceWorker"
        const val MAX_ATTEMPTS = 3
    }
}
