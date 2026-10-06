package app.tasker.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.tasker.core.database.entity.ContextSnapshotEntity
import app.tasker.core.database.entity.SourceEntity
import kotlinx.coroutines.flow.Flow

/** Context snapshots and sources of tasks. */
@Dao
interface ContentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSnapshot(snapshot: ContextSnapshotEntity)

    @Query("SELECT * FROM context_snapshot WHERE task_id = :taskId ORDER BY created_at DESC")
    fun observeSnapshots(taskId: String): Flow<List<ContextSnapshotEntity>>

    @Query("SELECT * FROM context_snapshot WHERE task_id = :taskId ORDER BY created_at DESC")
    suspend fun snapshots(taskId: String): List<ContextSnapshotEntity>

    /** Latest snapshot per task, for paused tasks on the Today screen. */
    @Query(
        "SELECT s.* FROM context_snapshot s WHERE s.created_at = " +
            "(SELECT MAX(created_at) FROM context_snapshot WHERE task_id = s.task_id) AND s.task_id IN (:taskIds)",
    )
    suspend fun latestSnapshots(taskIds: Collection<String>): List<ContextSnapshotEntity>

    @Query(
        "SELECT s.* FROM context_snapshot s WHERE s.created_at = " +
            "(SELECT MAX(created_at) FROM context_snapshot WHERE task_id = s.task_id)",
    )
    fun observeLatestSnapshots(): Flow<List<ContextSnapshotEntity>>

    @Query("SELECT * FROM context_snapshot")
    suspend fun allSnapshots(): List<ContextSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSource(source: SourceEntity)

    @Update
    suspend fun updateSource(source: SourceEntity)

    @Query("SELECT * FROM source WHERE task_id = :taskId")
    fun observeSources(taskId: String): Flow<List<SourceEntity>>

    @Query("SELECT * FROM source WHERE task_id = :taskId")
    suspend fun sources(taskId: String): List<SourceEntity>

    @Query("SELECT * FROM source")
    suspend fun allSources(): List<SourceEntity>

    @Query("SELECT DISTINCT task_id FROM source WHERE kind = :kind")
    suspend fun taskIdsWithSourceKind(kind: String): List<String>
}
