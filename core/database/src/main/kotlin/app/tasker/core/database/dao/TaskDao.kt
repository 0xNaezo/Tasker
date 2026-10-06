package app.tasker.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.tasker.core.database.entity.TagEntity
import app.tasker.core.database.entity.TaskEntity
import app.tasker.core.database.entity.TaskTagEntity
import app.tasker.core.database.entity.TaskWithTags
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: TaskEntity)

    /** Updates in place; never REPLACE, which would delete and re-insert the row. */
    @Update
    suspend fun update(task: TaskEntity)

    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Transaction
    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun getWithTags(id: String): TaskWithTags?

    @Transaction
    @Query("SELECT * FROM task WHERE id IN (:ids)")
    suspend fun getWithTags(ids: Collection<String>): List<TaskWithTags>

    @Transaction
    @Query("SELECT * FROM task WHERE id = :id")
    fun observe(id: String): Flow<TaskWithTags?>

    /** Every task that still needs work: the input of candidates, rules and the Today screen. */
    @Transaction
    @Query("SELECT * FROM task WHERE status IN ('OPEN', 'IN_PROGRESS', 'PAUSED')")
    suspend fun activeTasks(): List<TaskWithTags>

    @Transaction
    @Query("SELECT * FROM task WHERE status IN ('OPEN', 'IN_PROGRESS', 'PAUSED')")
    fun observeActive(): Flow<List<TaskWithTags>>

    /** Inbox: no bucket, no project (CAP-5); newest first. */
    @Transaction
    @Query("SELECT * FROM task WHERE status = 'OPEN' AND bucket IS NULL AND project_id IS NULL ORDER BY created_at DESC")
    fun observeInbox(): Flow<List<TaskWithTags>>

    @Query("SELECT COUNT(*) FROM task WHERE status = 'OPEN' AND bucket IS NULL AND project_id IS NULL")
    fun observeInboxCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM task WHERE status = 'OPEN' AND bucket IS NULL AND project_id IS NULL")
    suspend fun inboxCount(): Int

    @Transaction
    @Query(
        "SELECT * FROM task WHERE status IN ('OPEN', 'IN_PROGRESS', 'PAUSED') AND bucket = :bucket " +
            "ORDER BY position, created_at",
    )
    fun observeBucket(bucket: String): Flow<List<TaskWithTags>>

    @Query("SELECT * FROM task WHERE status IN ('OPEN', 'IN_PROGRESS', 'PAUSED') AND bucket IS :bucket ORDER BY position")
    suspend fun bucketTasks(bucket: String?): List<TaskEntity>

    @Transaction
    @Query("SELECT * FROM task WHERE project_id = :projectId AND status != 'ARCHIVED' ORDER BY status = 'DONE', position, created_at")
    fun observeProjectTasks(projectId: String): Flow<List<TaskWithTags>>

    @Query("SELECT * FROM task WHERE project_id = :projectId")
    suspend fun projectTasks(projectId: String): List<TaskEntity>

    @Query("SELECT * FROM task WHERE project_id IS NOT NULL AND status IN ('OPEN', 'IN_PROGRESS', 'PAUSED')")
    fun observeActiveProjectTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM task WHERE project_id IS NOT NULL AND status IN ('OPEN', 'IN_PROGRESS', 'PAUSED')")
    suspend fun activeProjectTasks(): List<TaskEntity>

    @Query("SELECT COUNT(*) FROM task WHERE status = 'IN_PROGRESS'")
    suspend fun inProgressCount(): Int

    @Query("SELECT id FROM task WHERE status = 'IN_PROGRESS' ORDER BY last_touched_at DESC")
    suspend fun inProgressIds(): List<String>

    @Query("SELECT COUNT(*) FROM task WHERE status = 'IN_PROGRESS'")
    fun observeInProgressCount(): Flow<Int>

    @Transaction
    @Query("SELECT * FROM task WHERE in_review = 1 AND status = 'OPEN' ORDER BY review_since")
    fun observeInReview(): Flow<List<TaskWithTags>>

    @Query("SELECT * FROM task WHERE in_review = 1 AND status = 'OPEN'")
    suspend fun inReview(): List<TaskEntity>

    /** Rule R2 input: unfinished tasks with a plan date before [today] that was not rolled yet. */
    @Query(
        "SELECT * FROM task WHERE status IN ('OPEN', 'IN_PROGRESS', 'PAUSED') AND plan_date IS NOT NULL " +
            "AND plan_date < :today AND (plan_date_rolled IS NULL OR plan_date_rolled != plan_date) ORDER BY plan_date",
    )
    suspend fun planDatePassed(today: Long): List<TaskEntity>

    @Query("SELECT * FROM task WHERE status IN ('OPEN', 'IN_PROGRESS', 'PAUSED') AND bucket = 'TODAY'")
    suspend fun todayBucket(): List<TaskEntity>

    /** Rule R3 pre-filter: open tasks outside review not touched since [touchedBefore]. */
    @Query("SELECT * FROM task WHERE status = 'OPEN' AND in_review = 0 AND last_touched_at < :touchedBefore")
    suspend fun ttlCandidates(touchedBefore: Long): List<TaskEntity>

    @Query("SELECT * FROM task WHERE status = 'OPEN' AND in_review = 1 AND review_skip_streak >= :minStreak")
    suspend fun autoArchiveCandidates(minStreak: Int): List<TaskEntity>

    /** Done log (LOG-1), newest first. */
    @Transaction
    @Query("SELECT * FROM task WHERE status = 'DONE' AND completed_at >= :from AND completed_at < :to ORDER BY completed_at DESC")
    fun observeDone(from: Long, to: Long): Flow<List<TaskWithTags>>

    @Query("SELECT * FROM task WHERE status = 'DONE' AND completed_at >= :from AND completed_at < :to")
    suspend fun doneBetween(from: Long, to: Long): List<TaskEntity>

    /** Recently completed tasks used as examples for AI estimates (§17.3). */
    @Query("SELECT * FROM task WHERE status = 'DONE' ORDER BY completed_at DESC LIMIT :limit")
    suspend fun recentDone(limit: Int): List<TaskEntity>

    @Transaction
    @Query("SELECT * FROM task WHERE status = 'ARCHIVED' ORDER BY archived_at DESC")
    fun pagingArchive(): PagingSource<Int, TaskWithTags>

    @Transaction
    @Query("SELECT * FROM task WHERE status = 'ARCHIVED' ORDER BY archived_at DESC")
    fun observeArchive(): Flow<List<TaskWithTags>>

    @Query("SELECT MAX(position) FROM task WHERE bucket IS :bucket AND status IN ('OPEN', 'IN_PROGRESS', 'PAUSED')")
    suspend fun maxPosition(bucket: String?): Long?

    @Query("SELECT MIN(position) FROM task WHERE bucket IS :bucket AND status IN ('OPEN', 'IN_PROGRESS', 'PAUSED')")
    suspend fun minPosition(bucket: String?): Long?

    @Query("UPDATE task SET position = :position WHERE id = :id")
    suspend fun setPosition(id: String, position: Long)

    @Query("SELECT * FROM task WHERE enrich_state = 'PENDING'")
    suspend fun pendingEnrichment(): List<TaskEntity>

    @Query("SELECT COUNT(*) FROM task")
    suspend fun count(): Int

    @Query("SELECT * FROM task")
    suspend fun all(): List<TaskEntity>

    @Query("DELETE FROM task WHERE id = :id")
    suspend fun delete(id: String)

    // Tags

    @Query("SELECT * FROM tag WHERE name_norm = :nameNorm")
    suspend fun tagByNorm(nameNorm: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTaskTags(links: List<TaskTagEntity>)

    @Query("DELETE FROM task_tag WHERE task_id = :taskId")
    suspend fun clearTaskTags(taskId: String)

    @Query("SELECT * FROM tag ORDER BY name")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tag")
    suspend fun allTags(): List<TagEntity>

    @Query("SELECT * FROM task_tag")
    suspend fun allTaskTags(): List<TaskTagEntity>

    @Query("DELETE FROM tag WHERE id NOT IN (SELECT tag_id FROM task_tag)")
    suspend fun deleteUnusedTags()
}
