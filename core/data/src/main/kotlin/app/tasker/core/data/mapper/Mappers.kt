package app.tasker.core.data.mapper

import app.tasker.core.database.entity.ContextSnapshotEntity
import app.tasker.core.database.entity.DayPlanEntity
import app.tasker.core.database.entity.DayPlanItemEntity
import app.tasker.core.database.entity.EventEntity
import app.tasker.core.database.entity.ProjectEntity
import app.tasker.core.database.entity.SourceEntity
import app.tasker.core.database.entity.TaskEntity
import app.tasker.core.database.entity.TaskWithTags
import app.tasker.core.domain.history.Codecs
import app.tasker.core.model.ContextSnapshot
import app.tasker.core.model.DayPlan
import app.tasker.core.model.DayPlanItem
import app.tasker.core.model.Deadline
import app.tasker.core.model.Event
import app.tasker.core.model.FieldChange
import app.tasker.core.model.FieldSource
import app.tasker.core.model.Project
import app.tasker.core.model.Reason
import app.tasker.core.model.ReminderOffsets
import app.tasker.core.model.Source
import app.tasker.core.model.Task
import app.tasker.core.model.TaskField
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonNull

// Storage conventions (tech plan §7.1): dates are local epoch days, timestamps are UTC epoch millis.

fun LocalDate.toEpochDayLong(): Long = toEpochDay()

fun Long.toLocalDate(): LocalDate = LocalDate.ofEpochDay(this)

fun Instant.toMillis(): Long = toEpochMilli()

fun Long.toInstant(): Instant = Instant.ofEpochMilli(this)

private val changesSerializer = ListSerializer(FieldChange.serializer())
private val paramsSerializer = MapSerializer(String.serializer(), String.serializer())

fun encodeChanges(changes: List<FieldChange>): String = DataJson.encodeToString(changesSerializer, changes)

/** JSON `null` values come back as Kotlin nulls; undo needs them as [JsonNull] ("the value was empty"). */
fun decodeChanges(text: String): List<FieldChange> = DataJson.decodeFromString(changesSerializer, text)
    .map { FieldChange(it.field, it.before ?: JsonNull, it.after ?: JsonNull) }

fun encodeFieldSources(sources: Map<TaskField, FieldSource>): String = Codecs.fieldSources.encode(sources).toString()

fun decodeFieldSources(text: String): Map<TaskField, FieldSource> =
    if (text.isBlank()) emptyMap() else Codecs.fieldSources.decode(DataJson.parseToJsonElement(text))

fun encodeReminders(offsets: ReminderOffsets?): String? = offsets?.minutesBefore?.joinToString(",")

fun decodeReminders(text: String?): ReminderOffsets? =
    text?.let { ReminderOffsets(it.split(',').mapNotNull { part -> part.trim().toIntOrNull() }) }

fun TaskEntity.toModel(tags: List<String> = emptyList()): Task = Task(
    id = id,
    title = title,
    rawInput = rawInput,
    note = note,
    status = status,
    bucket = bucket,
    position = position,
    deadline = deadlineDate?.let { date ->
        val time = deadlineTime?.let { LocalTime.ofSecondOfDay(it * SECONDS_PER_MINUTE) }
        Deadline(
            date = date.toLocalDate(),
            time = time,
            zone = if (time != null) deadlineZone?.let(ZoneId::of) else null,
        )
    },
    planDate = planDate?.toLocalDate(),
    estimate = estimate,
    projectId = projectId,
    tags = tags,
    postponeCount = postponeCount,
    postponePromptedAt = postponePromptedAt,
    planDateRolled = planDateRolled?.toLocalDate(),
    lastPostponeDay = lastPostponeDay?.toLocalDate(),
    carryOverSince = carryOverSince?.toLocalDate(),
    reminderOffsets = decodeReminders(reminderOffsets),
    lastTouchedAt = lastTouchedAt.toInstant(),
    inReview = inReview,
    reviewSince = reviewSince?.toInstant(),
    reviewSkipStreak = reviewSkipStreak,
    offPlan = offPlan,
    captureChannel = captureChannel,
    fieldSources = decodeFieldSources(fieldSources),
    enrichState = enrichState,
    createdAt = createdAt.toInstant(),
    completedAt = completedAt?.toInstant(),
    startedAt = startedAt?.toInstant(),
    archivedAt = archivedAt?.toInstant(),
    archiveReason = archiveReason,
    updatedAt = updatedAt.toInstant(),
)

/** Tags are sorted by their normalized name so that a stored task always reads back identically. */
fun TaskWithTags.toModel(): Task = task.toModel(tags.sortedBy { it.nameNorm }.map { it.name })

/** [zone] resolves the instant of a timed deadline without a stored zone (never the case for saved tasks). */
fun Task.toEntity(zone: ZoneId): TaskEntity = TaskEntity(
    id = id,
    title = title,
    rawInput = rawInput,
    note = note,
    status = status,
    bucket = bucket,
    position = position,
    deadlineDate = deadline?.date?.toEpochDayLong(),
    deadlineTime = deadline?.time?.let { it.toSecondOfDay() / SECONDS_PER_MINUTE.toInt() },
    deadlineZone = deadline?.takeIf { it.time != null }?.let { (it.zone ?: zone).id },
    deadlineAt = deadline?.instantOrNull(zone)?.toMillis(),
    planDate = planDate?.toEpochDayLong(),
    estimate = estimate,
    projectId = projectId,
    postponeCount = postponeCount,
    postponePromptedAt = postponePromptedAt,
    planDateRolled = planDateRolled?.toEpochDayLong(),
    lastPostponeDay = lastPostponeDay?.toEpochDayLong(),
    carryOverSince = carryOverSince?.toEpochDayLong(),
    reminderOffsets = encodeReminders(reminderOffsets),
    lastTouchedAt = lastTouchedAt.toMillis(),
    inReview = inReview,
    reviewSince = reviewSince?.toMillis(),
    reviewSkipStreak = reviewSkipStreak,
    offPlan = offPlan,
    captureChannel = captureChannel,
    fieldSources = encodeFieldSources(fieldSources),
    enrichState = enrichState,
    createdAt = createdAt.toMillis(),
    completedAt = completedAt?.toMillis(),
    startedAt = startedAt?.toMillis(),
    archivedAt = archivedAt?.toMillis(),
    archiveReason = archiveReason,
    updatedAt = updatedAt.toMillis(),
)

fun ProjectEntity.toModel(): Project = Project(
    id = id,
    name = name,
    outcome = outcome,
    status = status,
    position = position,
    lastActivityAt = lastActivityAt.toInstant(),
    inReview = inReview,
    reviewSince = reviewSince?.toInstant(),
    createdAt = createdAt.toInstant(),
    completedAt = completedAt?.toInstant(),
    archivedAt = archivedAt?.toInstant(),
    updatedAt = updatedAt.toInstant(),
)

fun Project.toEntity(): ProjectEntity = ProjectEntity(
    id = id,
    name = name,
    outcome = outcome,
    status = status,
    position = position,
    lastActivityAt = lastActivityAt.toMillis(),
    inReview = inReview,
    reviewSince = reviewSince?.toMillis(),
    createdAt = createdAt.toMillis(),
    completedAt = completedAt?.toMillis(),
    archivedAt = archivedAt?.toMillis(),
    updatedAt = updatedAt.toMillis(),
)

fun ContextSnapshotEntity.toModel(): ContextSnapshot = ContextSnapshot(
    id = id,
    taskId = taskId,
    text = text,
    inputKind = inputKind,
    nextStep = nextStep,
    createdAt = createdAt.toInstant(),
)

fun ContextSnapshot.toEntity(): ContextSnapshotEntity = ContextSnapshotEntity(
    id = id,
    taskId = taskId,
    text = text,
    inputKind = inputKind,
    nextStep = nextStep,
    createdAt = createdAt.toMillis(),
)

fun SourceEntity.toModel(): Source = Source(
    id = id,
    taskId = taskId,
    kind = kind,
    url = url,
    externalId = externalId,
    appPackage = appPackage,
    titleSnapshot = titleSnapshot,
    summary = summary,
    externalState = externalState,
    isActive = isActive,
    lastSyncedAt = lastSyncedAt?.toInstant(),
)

fun Source.toEntity(): SourceEntity = SourceEntity(
    id = id,
    taskId = taskId,
    kind = kind,
    url = url,
    externalId = externalId,
    appPackage = appPackage,
    titleSnapshot = titleSnapshot,
    summary = summary,
    externalState = externalState,
    isActive = isActive,
    lastSyncedAt = lastSyncedAt?.toMillis(),
)

fun DayPlanItemEntity.toModel(): DayPlanItem = DayPlanItem(
    date = date.toLocalDate(),
    taskId = taskId,
    position = position,
    minutes = minutes,
    origin = origin,
    candidateGroup = candidateGroup,
    outcome = outcome,
)

fun DayPlanItem.toEntity(): DayPlanItemEntity = DayPlanItemEntity(
    date = date.toEpochDayLong(),
    taskId = taskId,
    position = position,
    minutes = minutes,
    origin = origin,
    candidateGroup = candidateGroup,
    outcome = outcome,
)

fun DayPlanEntity.toModel(items: List<DayPlanItemEntity>): DayPlan = DayPlan(
    date = date.toLocalDate(),
    state = state,
    createdAt = createdAt.toInstant(),
    acceptedAt = acceptedAt?.toInstant(),
    workingMin = workingMin,
    busyMin = busyMin,
    capacityMin = capacityMin,
    plannedMin = plannedMin,
    items = items.map { it.toModel() }.sortedBy { it.position },
)

fun DayPlan.toEntity(): DayPlanEntity = DayPlanEntity(
    date = date.toEpochDayLong(),
    state = state,
    createdAt = createdAt.toMillis(),
    acceptedAt = acceptedAt?.toMillis(),
    workingMin = workingMin,
    busyMin = busyMin,
    capacityMin = capacityMin,
    plannedMin = plannedMin,
)

fun EventEntity.toModel(): Event = Event(
    id = id,
    batchId = batchId,
    entityType = entityType,
    entityId = entityId,
    actor = actor,
    type = type,
    changes = decodeChanges(changes),
    reason = reasonCode?.let { Reason(it, reasonParams?.let { p -> DataJson.decodeFromString(paramsSerializer, p) }.orEmpty()) },
    createdAt = createdAt.toInstant(),
    undoUntil = undoUntil?.toInstant(),
    undoneAt = undoneAt?.toInstant(),
    undoneBy = undoneBy,
)

fun Event.toEntity(): EventEntity = EventEntity(
    id = id,
    batchId = batchId,
    entityType = entityType,
    entityId = entityId,
    actor = actor,
    type = type,
    changes = encodeChanges(changes),
    reasonCode = reason?.code,
    reasonParams = reason?.params?.takeIf { it.isNotEmpty() }?.let { DataJson.encodeToString(paramsSerializer, it) },
    createdAt = createdAt.toMillis(),
    undoUntil = undoUntil?.toMillis(),
    undoneAt = undoneAt?.toMillis(),
    undoneBy = undoneBy,
)

private const val SECONDS_PER_MINUTE = 60L
