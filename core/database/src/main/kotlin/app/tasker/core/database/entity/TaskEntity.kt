package app.tasker.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import app.tasker.core.model.ArchiveReason
import app.tasker.core.model.Bucket
import app.tasker.core.model.CaptureChannel
import app.tasker.core.model.EnrichState
import app.tasker.core.model.Estimate
import app.tasker.core.model.TaskStatus

/**
 * Task row (tech plan §7.2). Dates without time are local epoch days; timestamps are UTC epoch millis.
 * Derived state ("overdue", "candidate", "no next step") is never stored.
 */
@Entity(
    tableName = "task",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("status", "bucket", "position"),
        Index("plan_date"),
        Index("deadline_date"),
        Index("project_id"),
        Index("in_review"),
        Index("completed_at"),
        Index("archived_at"),
    ],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "raw_input") val rawInput: String?,
    val note: String?,
    val status: TaskStatus,
    val bucket: Bucket?,
    val position: Long,
    @ColumnInfo(name = "deadline_date") val deadlineDate: Long?,
    @ColumnInfo(name = "deadline_time") val deadlineTime: Int?,
    @ColumnInfo(name = "deadline_zone") val deadlineZone: String?,
    @ColumnInfo(name = "deadline_at") val deadlineAt: Long?,
    @ColumnInfo(name = "plan_date") val planDate: Long?,
    val estimate: Estimate?,
    @ColumnInfo(name = "project_id") val projectId: String?,
    @ColumnInfo(name = "postpone_count") val postponeCount: Int,
    @ColumnInfo(name = "postpone_prompted_at") val postponePromptedAt: Int?,
    @ColumnInfo(name = "plan_date_rolled") val planDateRolled: Long?,
    @ColumnInfo(name = "last_postpone_day") val lastPostponeDay: Long?,
    @ColumnInfo(name = "carry_over_since") val carryOverSince: Long?,
    @ColumnInfo(name = "reminder_offsets") val reminderOffsets: String?,
    @ColumnInfo(name = "last_touched_at") val lastTouchedAt: Long,
    @ColumnInfo(name = "in_review") val inReview: Boolean,
    @ColumnInfo(name = "review_since") val reviewSince: Long?,
    @ColumnInfo(name = "review_skip_streak") val reviewSkipStreak: Int,
    @ColumnInfo(name = "off_plan") val offPlan: Boolean,
    @ColumnInfo(name = "capture_channel") val captureChannel: CaptureChannel,
    @ColumnInfo(name = "field_sources") val fieldSources: String,
    @ColumnInfo(name = "enrich_state") val enrichState: EnrichState,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "archive_reason") val archiveReason: ArchiveReason?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Task with its tags, loaded in one Room relation query. */
data class TaskWithTags(
    @Embedded val task: TaskEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(TaskTagEntity::class, parentColumn = "task_id", entityColumn = "tag_id"),
    )
    val tags: List<TagEntity>,
)
