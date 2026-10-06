package app.tasker.core.notifications

import android.content.BroadcastReceiver
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs [block] for a manifest receiver on the application [scope]: `goAsync()` keeps the process alive until the block
 * ends, so receivers stay short and never block the main thread. A failure is logged and never crashes the app.
 */
fun BroadcastReceiver.launchAsync(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Job {
    val pending = goAsync()
    return scope.launch(ReceiverErrors) {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

private val ReceiverErrors = CoroutineExceptionHandler { _, error -> Log.w("ReceiverWork", "Receiver work failed", error) }
