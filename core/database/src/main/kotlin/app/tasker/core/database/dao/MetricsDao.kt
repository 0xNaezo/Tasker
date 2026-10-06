package app.tasker.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import app.tasker.core.model.ArchiveReason

/** A value and how often it occurs. */
data class NameCount(val name: String, val count: Long)

/** A finished review or triage session. */
data class SessionSpan(val startedAt: Long, val endedAt: Long)

/**
 * Inputs of the weekly telemetry aggregates (tech plan §23). Every query returns counts or numbers, never texts.
 * Time bounds are epoch millis, day bounds are epoch days; both are half-open.
 */
@Dao
interface MetricsDao {
    @Query(
        "SELECT capture_channel AS name, COUNT(*) AS count FROM task " +
            "WHERE created_at >= :from AND created_at < :to GROUP BY capture_channel",
    )
    suspend fun createdByChannel(from: Long, to: Long): List<NameCount>

    /** Postpone counts of tasks completed or archived in the period. */
    @Query(
        "SELECT postpone_count FROM task " +
            "WHERE (completed_at >= :from AND completed_at < :to) OR (archived_at >= :from AND archived_at < :to)",
    )
    suspend fun postponeCountsOfFinished(from: Long, to: Long): List<Int>

    @Query("SELECT COUNT(*) FROM task WHERE archive_reason = :reason AND archived_at >= :from AND archived_at < :to")
    suspend fun archivedWithReason(reason: ArchiveReason, from: Long, to: Long): Long

    /** Outcomes of the items of accepted day plans. */
    @Query(
        "SELECT i.outcome AS name, COUNT(*) AS count FROM day_plan_item i JOIN day_plan p ON p.date = i.date " +
            "WHERE p.state = 'ACCEPTED' AND i.date >= :fromDay AND i.date < :toDay GROUP BY i.outcome",
    )
    suspend fun acceptedPlanOutcomes(fromDay: Long, toDay: Long): List<NameCount>

    @Query(
        "SELECT started_at AS startedAt, ended_at AS endedAt FROM review_session " +
            "WHERE logical_day >= :fromDay AND logical_day < :toDay AND ended_at IS NOT NULL",
    )
    suspend fun finishedSessions(fromDay: Long, toDay: Long): List<SessionSpan>

    @Query("SELECT COUNT(*) FROM activity_day WHERE day >= :fromDay AND day < :toDay")
    suspend fun activeDays(fromDay: Long, toDay: Long): Long

    /** The first logical day the app was used, or null before that. */
    @Query("SELECT MIN(day) FROM activity_day")
    suspend fun firstActiveDay(): Long?
}
