package app.tasker.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.tasker.core.database.entity.ActivityDayEntity
import app.tasker.core.database.entity.MaintenanceStateEntity
import app.tasker.core.database.entity.NotificationLogEntity
import app.tasker.core.database.entity.PendingNotificationEntity
import app.tasker.core.database.entity.ReviewCardEntity
import app.tasker.core.database.entity.ReviewSessionEntity
import kotlinx.coroutines.flow.Flow

/** Service tables (tech plan §7.6). */
@Dao
interface ServiceDao {
    // Review sessions and cards

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ReviewSessionEntity)

    @Query("SELECT * FROM review_session WHERE id = :id")
    suspend fun session(id: String): ReviewSessionEntity?

    @Query("UPDATE review_session SET ended_at = :at WHERE id = :id")
    suspend fun endSession(id: String, at: Long)

    @Insert
    suspend fun insertCard(card: ReviewCardEntity): Long

    @Query("SELECT * FROM review_card WHERE session_id = :sessionId AND entity_id = :entityId AND logical_day = :day LIMIT 1")
    suspend fun card(sessionId: String, entityId: String, day: Long): ReviewCardEntity?

    @Query(
        "UPDATE review_card SET decision = :decision, decided_at = :at " +
            "WHERE entity_id = :entityId AND logical_day = :day AND decision IS NULL",
    )
    suspend fun decideCards(entityId: String, day: Long, decision: String, at: Long)

    @Query("SELECT DISTINCT entity_id FROM review_card WHERE logical_day = :day AND kind = 'RELEVANCE' AND entity_type = 'TASK'")
    suspend fun shownTaskIds(day: Long): List<String>

    @Query(
        "SELECT DISTINCT entity_id FROM review_card WHERE logical_day = :day AND kind = 'RELEVANCE' " +
            "AND entity_type = 'TASK' AND decision IS NOT NULL",
    )
    suspend fun decidedTaskIds(day: Long): List<String>

    @Query("SELECT DISTINCT entity_id FROM review_card WHERE logical_day = :day AND decision IS NOT NULL")
    suspend fun decidedEntityIds(day: Long): List<String>

    @Query("SELECT * FROM review_session WHERE started_at >= :from")
    suspend fun sessionsSince(from: Long): List<ReviewSessionEntity>

    @Query("SELECT * FROM review_card")
    suspend fun allCards(): List<ReviewCardEntity>

    @Query("SELECT * FROM review_session")
    suspend fun allSessions(): List<ReviewSessionEntity>

    @Query("SELECT COUNT(*) FROM review_card WHERE logical_day = :day AND kind = 'RELEVANCE'")
    suspend fun relevanceCardsShown(day: Long): Int

    // Activity days

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertActivityDay(day: ActivityDayEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM activity_day WHERE day = :day)")
    suspend fun wasActive(day: Long): Boolean

    @Query("SELECT * FROM activity_day")
    suspend fun allActivityDays(): List<ActivityDayEntity>

    @Query("SELECT MIN(day) FROM activity_day")
    suspend fun firstActivityDay(): Long?

    // Notifications

    @Insert
    suspend fun logNotification(entry: NotificationLogEntity)

    @Query("SELECT COUNT(*) FROM notification_log WHERE logical_day = :day AND counts_in_budget = 1")
    suspend fun budgetUsed(day: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM notification_log WHERE key = :key)")
    suspend fun wasSent(key: String): Boolean

    @Query("SELECT * FROM notification_log WHERE sent_at >= :from ORDER BY sent_at DESC")
    suspend fun notificationsSince(from: Long): List<NotificationLogEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueuePending(entry: PendingNotificationEntity)

    @Query("SELECT * FROM pending_notification WHERE deliver_after <= :now ORDER BY created_at")
    suspend fun duePending(now: Long): List<PendingNotificationEntity>

    @Query("SELECT MIN(deliver_after) FROM pending_notification")
    suspend fun nextPendingDelivery(): Long?

    @Query("DELETE FROM pending_notification WHERE id IN (:ids)")
    suspend fun deletePending(ids: List<Long>)

    @Query("DELETE FROM pending_notification WHERE task_id = :taskId")
    suspend fun deletePendingForTask(taskId: String)

    // Maintenance marks

    @Query("SELECT value FROM maintenance_state WHERE key = :key")
    suspend fun mark(key: String): String?

    @Query("SELECT value FROM maintenance_state WHERE key = :key")
    fun observeMark(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMark(entry: MaintenanceStateEntity)

    @Query("SELECT * FROM maintenance_state")
    suspend fun allMarks(): List<MaintenanceStateEntity>
}
