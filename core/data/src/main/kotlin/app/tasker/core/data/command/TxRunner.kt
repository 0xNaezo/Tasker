package app.tasker.core.data.command

import androidx.room.withTransaction
import app.tasker.core.data.effects.CommitEffects
import app.tasker.core.data.effects.CommitInfo
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.Actor
import app.tasker.core.model.BatchId
import app.tasker.core.model.UuidV7
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Result of a command: its value and the batch to pass to undo; [batchId] is null when nothing changed. */
data class TxResult<T>(
    val value: T,
    val batchId: BatchId?,
    val info: CommitInfo,
)

/**
 * Executes commands (tech plan §4.3): one Room transaction applies the change, writes events and the search index;
 * post-commit effects run afterwards.
 */
@Singleton
class TxRunner @Inject constructor(
    private val db: TaskerDatabase,
    private val clock: DayClock,
    private val settings: SettingsRepository,
    private val effects: CommitEffects,
) {
    suspend fun <T> run(actor: Actor, block: suspend Tx.() -> T): TxResult<T> {
        val settingsSnapshot = settings.current()
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val tx = Tx(
            batchId = UuidV7.generate(now.toEpochMilli()),
            actor = actor,
            now = now,
            today = clock.logicalDay(now),
            settings = settingsSnapshot,
            zone = clock.zone(),
            db = db,
        )
        var info: CommitInfo? = null
        val value = db.withTransaction {
            val result = tx.block()
            info = tx.flush()
            result
        }
        val committed = checkNotNull(info)
        effects.dispatch(committed)
        return TxResult(value, tx.batchId.takeIf { tx.eventsWritten > 0 }, committed)
    }

    suspend fun <T> user(block: suspend Tx.() -> T): TxResult<T> = run(Actor.USER, block)
}
