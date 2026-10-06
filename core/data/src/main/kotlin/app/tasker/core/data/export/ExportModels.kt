package app.tasker.core.data.export

import app.tasker.core.model.AppSettings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `export.json` (DATA-1, tech plan §16): a versioned dump of the user's data. Every table is exported row by row with
 * the database column names, dates as ISO strings. Service tables are listed in [ExportFormat.SERVICE_TABLES]; a test
 * checks that every column of the database schema is either exported or explicitly service.
 */
@Serializable
data class ExportBundle(
    val format: String = ExportFormat.NAME,
    @SerialName("format_version") val formatVersion: Int = ExportFormat.VERSION,
    @SerialName("exported_at") val exportedAt: String,
    @SerialName("db_version") val dbVersion: Int,
    val settings: AppSettings,
    val tables: ExportTables,
)

@Serializable
data class ExportTables(
    val task: List<TaskRow> = emptyList(),
    val project: List<ProjectRow> = emptyList(),
    val tag: List<TagRow> = emptyList(),
    @SerialName("task_tag") val taskTag: List<TaskTagRow> = emptyList(),
    @SerialName("context_snapshot") val contextSnapshot: List<SnapshotRow> = emptyList(),
    val source: List<SourceRow> = emptyList(),
    @SerialName("day_plan") val dayPlan: List<DayPlanRow> = emptyList(),
    @SerialName("day_plan_item") val dayPlanItem: List<DayPlanItemRow> = emptyList(),
    val event: List<EventRow> = emptyList(),
    @SerialName("review_session") val reviewSession: List<ReviewSessionRow> = emptyList(),
    @SerialName("review_card") val reviewCard: List<ReviewCardRow> = emptyList(),
    @SerialName("activity_day") val activityDay: List<ActivityDayRow> = emptyList(),
    @SerialName("maintenance_state") val maintenanceState: List<MaintenanceRow> = emptyList(),
)

object ExportFormat {
    const val NAME = "tasker-export"
    const val VERSION = 1

    /** Tables that are rebuilt or only matter on this device: the search index and the notification ledger. */
    val SERVICE_TABLES = setOf("task_fts", "notification_log", "pending_notification", "room_master_table")
}

@Serializable
data class TaskRow(
    val id: String,
    val title: String,
    @SerialName("raw_input") val rawInput: String? = null,
    val note: String? = null,
    val status: String,
    val bucket: String? = null,
    val position: Long = 0,
    @SerialName("deadline_date") val deadlineDate: String? = null,
    @SerialName("deadline_time") val deadlineTime: String? = null,
    @SerialName("deadline_zone") val deadlineZone: String? = null,
    @SerialName("deadline_at") val deadlineAt: String? = null,
    @SerialName("plan_date") val planDate: String? = null,
    val estimate: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("postpone_count") val postponeCount: Int = 0,
    @SerialName("postpone_prompted_at") val postponePromptedAt: Int? = null,
    @SerialName("plan_date_rolled") val planDateRolled: String? = null,
    @SerialName("last_postpone_day") val lastPostponeDay: String? = null,
    @SerialName("carry_over_since") val carryOverSince: String? = null,
    @SerialName("reminder_offsets") val reminderOffsets: List<Int>? = null,
    @SerialName("last_touched_at") val lastTouchedAt: String,
    @SerialName("in_review") val inReview: Boolean = false,
    @SerialName("review_since") val reviewSince: String? = null,
    @SerialName("review_skip_streak") val reviewSkipStreak: Int = 0,
    @SerialName("off_plan") val offPlan: Boolean = false,
    @SerialName("capture_channel") val captureChannel: String = "BAR",
    @SerialName("field_sources") val fieldSources: Map<String, String> = emptyMap(),
    @SerialName("enrich_state") val enrichState: String = "NONE",
    @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("archived_at") val archivedAt: String? = null,
    @SerialName("archive_reason") val archiveReason: String? = null,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ProjectRow(
    val id: String,
    val name: String,
    val outcome: String? = null,
    val status: String,
    val position: Long = 0,
    @SerialName("last_activity_at") val lastActivityAt: String,
    @SerialName("in_review") val inReview: Boolean = false,
    @SerialName("review_since") val reviewSince: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("archived_at") val archivedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class TagRow(
    val id: String,
    val name: String,
    @SerialName("name_norm") val nameNorm: String,
)

@Serializable
data class TaskTagRow(
    @SerialName("task_id") val taskId: String,
    @SerialName("tag_id") val tagId: String,
)

@Serializable
data class SnapshotRow(
    val id: String,
    @SerialName("task_id") val taskId: String,
    val text: String,
    @SerialName("input_kind") val inputKind: String,
    @SerialName("next_step") val nextStep: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class SourceRow(
    val id: String,
    @SerialName("task_id") val taskId: String,
    val kind: String,
    val url: String? = null,
    @SerialName("external_id") val externalId: String? = null,
    @SerialName("app_package") val appPackage: String? = null,
    @SerialName("title_snapshot") val titleSnapshot: String? = null,
    val summary: String? = null,
    @SerialName("external_state") val externalState: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
)

@Serializable
data class DayPlanRow(
    val date: String,
    val state: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("accepted_at") val acceptedAt: String? = null,
    @SerialName("working_min") val workingMin: Int = 0,
    @SerialName("busy_min") val busyMin: Int = 0,
    @SerialName("capacity_min") val capacityMin: Int = 0,
    @SerialName("planned_min") val plannedMin: Int = 0,
)

@Serializable
data class DayPlanItemRow(
    val date: String,
    @SerialName("task_id") val taskId: String,
    val position: Int,
    val minutes: Int,
    val origin: String,
    @SerialName("candidate_group") val candidateGroup: String? = null,
    val outcome: String,
)

@Serializable
data class EventRow(
    val id: String,
    @SerialName("batch_id") val batchId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val actor: String,
    val type: String,
    val changes: kotlinx.serialization.json.JsonElement,
    @SerialName("reason_code") val reasonCode: String? = null,
    @SerialName("reason_params") val reasonParams: Map<String, String>? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("undo_until") val undoUntil: String? = null,
    @SerialName("undone_at") val undoneAt: String? = null,
    @SerialName("undone_by") val undoneBy: String? = null,
)

@Serializable
data class ReviewSessionRow(
    val id: String,
    val kind: String,
    @SerialName("logical_day") val logicalDay: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
)

@Serializable
data class ReviewCardRow(
    val id: Long,
    @SerialName("session_id") val sessionId: String,
    val kind: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    @SerialName("logical_day") val logicalDay: String,
    @SerialName("shown_at") val shownAt: String,
    val decision: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
)

@Serializable
data class ActivityDayRow(
    val day: String,
    @SerialName("first_seen_at") val firstSeenAt: String,
)

@Serializable
data class MaintenanceRow(
    val key: String,
    val value: String,
)
