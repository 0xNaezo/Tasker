package app.tasker.core.scheduling.plan

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.tasker.core.notifications.NotificationFactory
import app.tasker.core.scheduling.Scheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Expedited work started by the morning plan alarm (tech plan §12.3): runs [MorningPlan] and arms the alarm for the
 * next working day. Failures are retried a few times; a late plan is skipped by [MorningPlan] itself.
 */
@HiltWorker
class MorningPlanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val morningPlan: MorningPlan,
    private val scheduler: Scheduler,
    private val notifications: NotificationFactory,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val outcome = runCatching { morningPlan.run() }
        currentCoroutineContext().ensureActive()
        scheduler.scheduleMorningPlan()
        val error = outcome.exceptionOrNull() ?: return Result.success()
        Log.w(TAG, "Morning plan failed", error)
        return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    /** Expedited work runs as a short foreground job before Android 12 and needs a notification there. */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(FOREGROUND_ID, notifications.preparingPlan())

    private companion object {
        const val TAG = "MorningPlanWorker"
        const val MAX_ATTEMPTS = 3
        const val FOREGROUND_ID = 1001
    }
}
