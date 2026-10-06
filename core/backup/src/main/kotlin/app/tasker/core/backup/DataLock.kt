package app.tasker.core.backup

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes whole-database operations of this module: export, import, restore and the daily backup. Import clears
 * the tables before it fills them; without the lock a backup taken in between would save an empty database over the
 * last good copy.
 */
@Singleton
class DataLock @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
