package app.tasker.platform

import android.util.Log
import app.tasker.core.ai.AiGatewayProvider
import app.tasker.core.ai.EnrichmentScheduler
import app.tasker.core.backup.BackupScheduler
import app.tasker.core.data.di.ApplicationScope
import app.tasker.core.scheduling.Scheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Work started with every process: crash reports (with consent), notification channels, alarms and the daily
 * catch-up, the daily backup, and the AI queue — tasks left waiting after a failed key resume when AI is ready.
 */
@Singleton
class AppStartup @Inject constructor(
    private val crashReporting: CrashReporting,
    private val scheduler: Scheduler,
    private val backups: BackupScheduler,
    private val ai: AiGatewayProvider,
    private val enrichment: EnrichmentScheduler,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    fun run() {
        crashReporting.start()
        scheduler.onAppStart()
        backups.schedule()
        scope.launch {
            runCatching { if (ai.isReady()) enrichment.enqueue() }
                .onFailure { Log.w(TAG, "AI queue was not resumed", it) }
        }
    }

    private companion object {
        const val TAG = "AppStartup"
    }
}
