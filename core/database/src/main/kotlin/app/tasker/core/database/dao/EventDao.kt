package app.tasker.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.tasker.core.database.entity.EventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(events: List<EventEntity>)

    /** Task or project history (newest first). */
    @Query("SELECT * FROM event WHERE entity_id = :entityId ORDER BY created_at DESC, id DESC")
    fun observeHistory(entityId: String): Flow<List<EventEntity>>

    @Query("SELECT * FROM event WHERE batch_id = :batchId ORDER BY created_at, id")
    suspend fun batch(batchId: String): List<EventEntity>

    /** Automation journal (AUT-1): every non-user event, newest first. */
    @Query("SELECT * FROM event WHERE actor != 'USER' AND created_at >= :since ORDER BY created_at DESC, id DESC")
    fun observeAutomation(since: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM event WHERE actor != 'USER' AND created_at >= :since ORDER BY created_at DESC, id DESC")
    suspend fun automationSince(since: Long): List<EventEntity>

    @Query("UPDATE event SET undone_at = :at, undone_by = :undoBatch WHERE batch_id = :batchId AND undone_at IS NULL")
    suspend fun markBatchUndone(batchId: String, at: Long, undoBatch: String)

    @Query(
        "UPDATE event SET undone_at = :at, undone_by = :undoBatch " +
            "WHERE batch_id = :batchId AND entity_id = :entityId AND undone_at IS NULL",
    )
    suspend fun markEntityUndone(batchId: String, entityId: String, at: Long, undoBatch: String)

    @Query("SELECT * FROM event WHERE entity_id = :entityId AND type = :type ORDER BY created_at DESC, id DESC LIMIT 1")
    suspend fun latestOfType(entityId: String, type: String): EventEntity?

    /** Latest AI fill for a task: its `before` values restore the field on "undo AI" (AI-4). */
    @Query("SELECT * FROM event WHERE entity_id = :taskId AND type = 'AI_FILLED' AND undone_at IS NULL ORDER BY created_at DESC LIMIT 1")
    suspend fun latestAiFill(taskId: String): EventEntity?

    /** Postpone events of a logical day for the day summary (LOG-2). */
    @Query(
        "SELECT COUNT(DISTINCT entity_id) FROM event WHERE type IN ('POSTPONED', 'ROLLED_OVER') AND undone_at IS NULL " +
            "AND created_at >= :from AND created_at < :to",
    )
    suspend fun postponedTaskCount(from: Long, to: Long): Int

    @Query("SELECT * FROM event WHERE type = 'ARCHIVED' AND reason_code = 'TTL_SKIPS' AND created_at >= :from")
    suspend fun ttlArchivesSince(from: Long): List<EventEntity>

    @Query("DELETE FROM event WHERE entity_id = :entityId")
    suspend fun deleteForEntity(entityId: String)

    @Query("SELECT * FROM event ORDER BY created_at, id")
    suspend fun all(): List<EventEntity>

    @Query("SELECT COUNT(*) FROM event")
    suspend fun count(): Int
}
