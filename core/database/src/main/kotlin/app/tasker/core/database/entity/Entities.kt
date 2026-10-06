package app.tasker.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import app.tasker.core.model.ProjectStatus
import app.tasker.core.model.SnapshotInputKind
import app.tasker.core.model.SourceKind

@Entity(tableName = "project", indices = [Index("status"), Index("in_review")])
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val outcome: String?,
    val status: ProjectStatus,
    val position: Long,
    @ColumnInfo(name = "last_activity_at") val lastActivityAt: Long,
    @ColumnInfo(name = "in_review") val inReview: Boolean,
    @ColumnInfo(name = "review_since") val reviewSince: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "tag", indices = [Index(value = ["name_norm"], unique = true)])
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "name_norm") val nameNorm: String,
)

@Entity(
    tableName = "task_tag",
    primaryKeys = ["task_id", "tag_id"],
    foreignKeys = [
        ForeignKey(TaskEntity::class, ["id"], ["task_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tag_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tag_id")],
)
data class TaskTagEntity(
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
)

/** Context snapshot (EXC-3, EXC-4): history is kept, the latest is shown first. */
@Entity(
    tableName = "context_snapshot",
    foreignKeys = [ForeignKey(TaskEntity::class, ["id"], ["task_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("task_id", "created_at")],
)
data class ContextSnapshotEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    val text: String,
    @ColumnInfo(name = "input_kind") val inputKind: SnapshotInputKind,
    @ColumnInfo(name = "next_step") val nextStep: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "source",
    foreignKeys = [ForeignKey(TaskEntity::class, ["id"], ["task_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("task_id"), Index("external_id")],
)
data class SourceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    val kind: SourceKind,
    val url: String?,
    @ColumnInfo(name = "external_id") val externalId: String?,
    @ColumnInfo(name = "app_package") val appPackage: String?,
    @ColumnInfo(name = "title_snapshot") val titleSnapshot: String?,
    val summary: String?,
    @ColumnInfo(name = "external_state") val externalState: String?,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long?,
)

/** Day plan with the capacity snapshot taken at acceptance (tech plan §7.4). */
@Entity(tableName = "day_plan")
data class DayPlanEntity(
    @PrimaryKey val date: Long,
    val state: app.tasker.core.model.PlanState,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "accepted_at") val acceptedAt: Long?,
    @ColumnInfo(name = "working_min") val workingMin: Int,
    @ColumnInfo(name = "busy_min") val busyMin: Int,
    @ColumnInfo(name = "capacity_min") val capacityMin: Int,
    @ColumnInfo(name = "planned_min") val plannedMin: Int,
)

@Entity(
    tableName = "day_plan_item",
    primaryKeys = ["date", "task_id"],
    foreignKeys = [
        ForeignKey(DayPlanEntity::class, ["date"], ["date"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TaskEntity::class, ["id"], ["task_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("task_id")],
)
data class DayPlanItemEntity(
    val date: Long,
    @ColumnInfo(name = "task_id") val taskId: String,
    val position: Int,
    val minutes: Int,
    val origin: app.tasker.core.model.PlanItemOrigin,
    @ColumnInfo(name = "candidate_group") val candidateGroup: app.tasker.core.model.CandidateGroup?,
    val outcome: app.tasker.core.model.PlanItemOutcome,
)

/** Event log (tech plan §7.5): task history, automation journal, undo and metrics are views of this table. */
@Entity(
    tableName = "event",
    indices = [Index("entity_id", "created_at"), Index("actor", "created_at"), Index("batch_id")],
)
data class EventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "entity_type") val entityType: app.tasker.core.model.EntityType,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val actor: app.tasker.core.model.Actor,
    val type: app.tasker.core.model.EventType,
    val changes: String,
    @ColumnInfo(name = "reason_code") val reasonCode: app.tasker.core.model.ReasonCode?,
    @ColumnInfo(name = "reason_params") val reasonParams: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "undo_until") val undoUntil: Long?,
    @ColumnInfo(name = "undone_at") val undoneAt: Long?,
    @ColumnInfo(name = "undone_by") val undoneBy: String?,
)

@Entity(tableName = "review_session", indices = [Index("logical_day")])
data class ReviewSessionEntity(
    @PrimaryKey val id: String,
    val kind: app.tasker.core.model.ReviewKind,
    @ColumnInfo(name = "logical_day") val logicalDay: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
)

/** A review card shown to the user; used for skip counting (TTL-6) and maintenance time metrics. */
@Entity(
    tableName = "review_card",
    foreignKeys = [ForeignKey(ReviewSessionEntity::class, ["id"], ["session_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("session_id"), Index("logical_day", "entity_id"), Index("entity_id")],
)
data class ReviewCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val kind: app.tasker.core.model.ReviewKind,
    @ColumnInfo(name = "entity_type") val entityType: app.tasker.core.model.EntityType,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "logical_day") val logicalDay: Long,
    @ColumnInfo(name = "shown_at") val shownAt: Long,
    val decision: app.tasker.core.model.ReviewDecision?,
    @ColumnInfo(name = "decided_at") val decidedAt: Long?,
)

/** Logical days when the user opened the app (rule R1, §10.6). */
@Entity(tableName = "activity_day")
data class ActivityDayEntity(
    @PrimaryKey val day: Long,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
)

@Entity(tableName = "notification_log", indices = [Index("logical_day", "type"), Index(value = ["key"])])
data class NotificationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val key: String,
    @ColumnInfo(name = "task_id") val taskId: String?,
    @ColumnInfo(name = "logical_day") val logicalDay: Long,
    @ColumnInfo(name = "sent_at") val sentAt: Long,
    @ColumnInfo(name = "counts_in_budget") val countsInBudget: Boolean,
)

/** Notifications deferred by quiet hours (NTF-4); delivered as one group afterwards. */
@Entity(tableName = "pending_notification", indices = [Index(value = ["key"], unique = true)])
data class PendingNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val key: String,
    @ColumnInfo(name = "task_id") val taskId: String?,
    val title: String,
    val text: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "deliver_after") val deliverAfter: Long,
)

/** Last processed day per rule and other catch-up marks (tech plan §11.1). */
@Entity(tableName = "maintenance_state")
data class MaintenanceStateEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/**
 * Full-text index (SRC-1, tech plan §15). The platform SQLite cannot load a custom tokenizer, so the text is
 * normalized and stemmed before it is written; columns hold the normalized forms.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["task_id"])
@Entity(tableName = "task_fts")
data class TaskFtsEntity(
    @ColumnInfo(name = "task_id") val taskId: String,
    val title: String,
    val note: String,
    val snapshots: String,
    val tags: String,
)
