package app.tasker.core.backup

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException

/**
 * The daily backup (tech plan §12.3, §16), scheduled by [BackupScheduler]. A failed copy into the user folder does not
 * fail the work; a failed local write (e.g. a full disk) is retried a few times, then waits for the next day.
 *
 * Needs the app to create workers through `HiltWorkerFactory` (see the module README).
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backups: BackupService,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        backups.backUp()
        Result.success()
    } catch (e: IOException) {
        Log.w(TAG, "Daily backup failed (attempt ${runAttemptCount + 1})", e)
        if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    private companion object {
        const val TAG = "BackupWorker"
        const val MAX_ATTEMPTS = 3
    }
}
