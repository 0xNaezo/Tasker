package app.tasker.core.ui.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs a command from a ViewModel and returns its result or the failure. Cancellation is never swallowed, so a
 * cleared ViewModel stops its work.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
