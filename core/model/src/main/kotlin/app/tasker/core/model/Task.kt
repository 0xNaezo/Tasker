package app.tasker.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Hard external date (DAT-1). A deadline without time belongs to the local day; a deadline with time
 * keeps the zone it was created in, so its instant never shifts when the device zone changes (§7.8).
 */
data class Deadline(
    val date: LocalDate,
    val time: LocalTime? = null,
    val zone: ZoneId? = null,
) {
    init {
        require(time != null || zone == null) { "A date-only deadline has no zone" }
    }

    val hasTime: Boolean get() = time != null

    /** UTC instant of a timed deadline; `null` for date-only deadlines. */
    fun instantOrNull(defaultZone: ZoneId): Instant? =
        time?.let { ZonedDateTime.of(date, it, zone ?: defaultZone).toInstant() }
}

/** Individual reminder settings for a deadline (NTF-2). `minutesBefore` of 1440 means "the day before". */
data class ReminderOffsets(val minutesBefore: List<Int>)

data class Task(
    val id: TaskId,
    val title: String,
    val rawInput: String? = null,
    val note: String? = null,
    val status: TaskStatus = TaskStatus.OPEN,
    val bucket: Bucket? = null,
    val position: Long = 0,
    val deadline: Deadline? = null,
    val planDate: LocalDate? = null,
    val estimate: Estimate? = null,
    val projectId: ProjectId? = null,
    val tags: List<String> = emptyList(),
    val postponeCount: Int = 0,
    val postponePromptedAt: Int? = null,
    val planDateRolled: LocalDate? = null,
    val lastPostponeDay: LocalDate? = null,
    val carryOverSince: LocalDate? = null,
    val reminderOffsets: ReminderOffsets? = null,
    val lastTouchedAt: Instant,
    val inReview: Boolean = false,
    val reviewSince: Instant? = null,
    val reviewSkipStreak: Int = 0,
    val offPlan: Boolean = false,
    val captureChannel: CaptureChannel = CaptureChannel.BAR,
    val fieldSources: Map<TaskField, FieldSource> = emptyMap(),
    val enrichState: EnrichState = EnrichState.NONE,
    val createdAt: Instant,
    val completedAt: Instant? = null,
    val startedAt: Instant? = null,
    val archivedAt: Instant? = null,
    val archiveReason: ArchiveReason? = null,
    val updatedAt: Instant = createdAt,
) {
    val isInbox: Boolean get() = bucket == null && projectId == null && status == TaskStatus.OPEN

    fun sourceOf(field: TaskField): FieldSource? = fieldSources[field]
}

data class Project(
    val id: ProjectId,
    val name: String,
    val outcome: String? = null,
    val status: ProjectStatus = ProjectStatus.ACTIVE,
    val position: Long = 0,
    val lastActivityAt: Instant,
    val inReview: Boolean = false,
    val reviewSince: Instant? = null,
    val createdAt: Instant,
    val completedAt: Instant? = null,
    val archivedAt: Instant? = null,
    val updatedAt: Instant = createdAt,
)

data class Tag(val id: TagId, val name: String)

/** "Where I stopped / what's next" note saved on pause (EXC-3, EXC-4). */
data class ContextSnapshot(
    val id: String,
    val taskId: TaskId,
    val text: String,
    val inputKind: SnapshotInputKind,
    val nextStep: String? = null,
    val createdAt: Instant,
)

/** External object the task links to; the external system stays the source of truth (ТЗ §5.8). */
data class Source(
    val id: String,
    val taskId: TaskId,
    val kind: SourceKind,
    val url: String? = null,
    val externalId: String? = null,
    val appPackage: String? = null,
    val titleSnapshot: String? = null,
    val summary: String? = null,
    val externalState: String? = null,
    val isActive: Boolean = true,
    val lastSyncedAt: Instant? = null,
)
