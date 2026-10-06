package app.tasker.core.domain.history

import app.tasker.core.model.ArchiveReason
import app.tasker.core.model.Bucket
import app.tasker.core.model.EnrichState
import app.tasker.core.model.Estimate
import app.tasker.core.model.FieldChange
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectStatus
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import kotlinx.serialization.json.JsonElement

/**
 * How undo treats a column (tech plan §8.4):
 * VALUE returns to `before` if nobody changed it since; COUNTER is reverted by the inverse delta (not below 0);
 * MARKER (idempotency marks of rules) is never reverted by automation undo, otherwise the rule would fire again.
 */
enum class ColumnKind { VALUE, COUNTER, MARKER }

class Column<E, T>(
    val key: String,
    val kind: ColumnKind,
    private val getter: (E) -> T,
    private val setter: (E, T) -> E,
    private val codec: ValueCodec<T>,
) {
    fun encode(entity: E): JsonElement = codec.encode(getter(entity))

    fun apply(entity: E, json: JsonElement): E = setter(entity, codec.decode(json))

    fun counterValue(json: JsonElement?): Int = json?.let { (codec.decode(it) as? Number)?.toInt() } ?: 0
}

abstract class ColumnSet<E> {
    abstract val all: List<Column<E, *>>

    private val byKey by lazy { all.associateBy { it.key } }

    fun byKey(key: String): Column<E, *>? = byKey[key]

    /** Field-level changes between two states of one entity, in declaration order. */
    fun diff(before: E, after: E): List<FieldChange> = all.mapNotNull { column ->
        val old = column.encode(before)
        val new = column.encode(after)
        if (old == new) null else FieldChange(column.key, old, new)
    }
}

/** Tracked task columns. `last_touched_at` and `updated_at` are implied by every event and not tracked. */
object TaskColumns : ColumnSet<Task>() {
    val TITLE = Column<Task, String>("title", ColumnKind.VALUE, { it.title }, { t, v -> t.copy(title = v) }, Codecs.string)
    val NOTE = Column<Task, String?>("note", ColumnKind.VALUE, { it.note }, { t, v -> t.copy(note = v) }, Codecs.nullableString)
    val STATUS = Column<Task, TaskStatus>(
        "status",
        ColumnKind.VALUE,
        { it.status },
        { t, v -> t.copy(status = v) },
        Codecs.enumCodec(),
    )
    val BUCKET =
        Column<Task, Bucket?>("bucket", ColumnKind.VALUE, { it.bucket }, { t, v -> t.copy(bucket = v) }, Codecs.nullableEnumCodec())
    val POSITION = Column<Task, Long>("position", ColumnKind.VALUE, { it.position }, { t, v -> t.copy(position = v) }, Codecs.long)
    val DEADLINE = Column<Task, app.tasker.core.model.Deadline?>(
        "deadline",
        ColumnKind.VALUE,
        { it.deadline },
        { t, v -> t.copy(deadline = v) },
        Codecs.deadline,
    )
    val PLAN_DATE = Column("plan_date", ColumnKind.VALUE, Task::planDate, { t: Task, v -> t.copy(planDate = v) }, Codecs.nullableDate)
    val ESTIMATE = Column<Task, Estimate?>(
        "estimate",
        ColumnKind.VALUE,
        { it.estimate },
        { t, v -> t.copy(estimate = v) },
        Codecs.nullableEnumCodec(),
    )
    val PROJECT = Column("project_id", ColumnKind.VALUE, Task::projectId, { t: Task, v -> t.copy(projectId = v) }, Codecs.nullableString)
    val TAGS = Column("tags", ColumnKind.VALUE, Task::tags, { t: Task, v -> t.copy(tags = v) }, Codecs.stringList)
    val POSTPONE_COUNT = Column(
        "postpone_count",
        ColumnKind.COUNTER,
        Task::postponeCount,
        { t: Task, v -> t.copy(postponeCount = v) },
        Codecs.int,
    )
    val POSTPONE_PROMPTED_AT = Column(
        "postpone_prompted_at",
        ColumnKind.VALUE,
        Task::postponePromptedAt,
        { t: Task, v -> t.copy(postponePromptedAt = v) },
        Codecs.nullableInt,
    )
    val PLAN_DATE_ROLLED = Column(
        "plan_date_rolled",
        ColumnKind.MARKER,
        Task::planDateRolled,
        { t: Task, v -> t.copy(planDateRolled = v) },
        Codecs.nullableDate,
    )
    val LAST_POSTPONE_DAY = Column(
        "last_postpone_day",
        ColumnKind.MARKER,
        Task::lastPostponeDay,
        { t: Task, v -> t.copy(lastPostponeDay = v) },
        Codecs.nullableDate,
    )
    val CARRY_OVER_SINCE = Column(
        "carry_over_since",
        ColumnKind.VALUE,
        Task::carryOverSince,
        { t: Task, v -> t.copy(carryOverSince = v) },
        Codecs.nullableDate,
    )
    val REMINDERS = Column(
        "reminder_offsets",
        ColumnKind.VALUE,
        Task::reminderOffsets,
        { t: Task, v -> t.copy(reminderOffsets = v) },
        Codecs.reminders,
    )
    val IN_REVIEW = Column("in_review", ColumnKind.VALUE, Task::inReview, { t: Task, v -> t.copy(inReview = v) }, Codecs.boolean)
    val REVIEW_SINCE = Column(
        "review_since",
        ColumnKind.VALUE,
        Task::reviewSince,
        { t: Task, v -> t.copy(reviewSince = v) },
        Codecs.nullableInstant,
    )
    val REVIEW_SKIP_STREAK = Column(
        "review_skip_streak",
        ColumnKind.COUNTER,
        Task::reviewSkipStreak,
        { t: Task, v -> t.copy(reviewSkipStreak = v) },
        Codecs.int,
    )
    val OFF_PLAN = Column("off_plan", ColumnKind.VALUE, Task::offPlan, { t: Task, v -> t.copy(offPlan = v) }, Codecs.boolean)
    val FIELD_SOURCES = Column(
        "field_sources",
        ColumnKind.VALUE,
        Task::fieldSources,
        { t: Task, v -> t.copy(fieldSources = v) },
        Codecs.fieldSources,
    )
    val ENRICH_STATE = Column<Task, EnrichState>(
        "enrich_state",
        ColumnKind.MARKER,
        { it.enrichState },
        { t, v -> t.copy(enrichState = v) },
        Codecs.enumCodec(),
    )
    val COMPLETED_AT = Column(
        "completed_at",
        ColumnKind.VALUE,
        Task::completedAt,
        { t: Task, v -> t.copy(completedAt = v) },
        Codecs.nullableInstant,
    )
    val STARTED_AT =
        Column("started_at", ColumnKind.VALUE, Task::startedAt, { t: Task, v -> t.copy(startedAt = v) }, Codecs.nullableInstant)
    val ARCHIVED_AT = Column(
        "archived_at",
        ColumnKind.VALUE,
        Task::archivedAt,
        { t: Task, v -> t.copy(archivedAt = v) },
        Codecs.nullableInstant,
    )
    val ARCHIVE_REASON = Column<Task, ArchiveReason?>(
        "archive_reason",
        ColumnKind.VALUE,
        { it.archiveReason },
        { t, v -> t.copy(archiveReason = v) },
        Codecs.nullableEnumCodec(),
    )

    override val all: List<Column<Task, *>> = listOf(
        TITLE, NOTE, STATUS, BUCKET, POSITION, DEADLINE, PLAN_DATE, ESTIMATE, PROJECT, TAGS,
        POSTPONE_COUNT, POSTPONE_PROMPTED_AT, PLAN_DATE_ROLLED, LAST_POSTPONE_DAY, CARRY_OVER_SINCE,
        REMINDERS, IN_REVIEW, REVIEW_SINCE, REVIEW_SKIP_STREAK, OFF_PLAN, FIELD_SOURCES, ENRICH_STATE,
        COMPLETED_AT, STARTED_AT, ARCHIVED_AT, ARCHIVE_REASON,
    )
}

object ProjectColumns : ColumnSet<Project>() {
    val NAME = Column("name", ColumnKind.VALUE, Project::name, { p: Project, v -> p.copy(name = v) }, Codecs.string)
    val OUTCOME = Column("outcome", ColumnKind.VALUE, Project::outcome, { p: Project, v -> p.copy(outcome = v) }, Codecs.nullableString)
    val STATUS = Column<Project, ProjectStatus>(
        "status",
        ColumnKind.VALUE,
        { it.status },
        { p, v -> p.copy(status = v) },
        Codecs.enumCodec(),
    )
    val POSITION = Column("position", ColumnKind.VALUE, Project::position, { p: Project, v -> p.copy(position = v) }, Codecs.long)
    val IN_REVIEW = Column("in_review", ColumnKind.VALUE, Project::inReview, { p: Project, v -> p.copy(inReview = v) }, Codecs.boolean)
    val REVIEW_SINCE = Column(
        "review_since",
        ColumnKind.VALUE,
        Project::reviewSince,
        { p: Project, v -> p.copy(reviewSince = v) },
        Codecs.nullableInstant,
    )
    val LAST_ACTIVITY_AT = Column(
        "last_activity_at",
        ColumnKind.MARKER,
        Project::lastActivityAt,
        { p: Project, v -> p.copy(lastActivityAt = v) },
        Codecs.instant,
    )
    val COMPLETED_AT = Column(
        "completed_at",
        ColumnKind.VALUE,
        Project::completedAt,
        { p: Project, v -> p.copy(completedAt = v) },
        Codecs.nullableInstant,
    )
    val ARCHIVED_AT = Column(
        "archived_at",
        ColumnKind.VALUE,
        Project::archivedAt,
        { p: Project, v -> p.copy(archivedAt = v) },
        Codecs.nullableInstant,
    )

    override val all: List<Column<Project, *>> = listOf(
        NAME, OUTCOME, STATUS, POSITION, IN_REVIEW, REVIEW_SINCE, LAST_ACTIVITY_AT, COMPLETED_AT, ARCHIVED_AT,
    )
}
