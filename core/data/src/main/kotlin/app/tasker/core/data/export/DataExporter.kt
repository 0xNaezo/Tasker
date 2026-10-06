package app.tasker.core.data.export

import androidx.room.withTransaction
import app.tasker.core.data.mapper.DataJson
import app.tasker.core.data.mapper.decodeFieldSources
import app.tasker.core.data.mapper.decodeReminders
import app.tasker.core.data.mapper.encodeFieldSources
import app.tasker.core.data.mapper.encodeReminders
import app.tasker.core.data.search.SearchNormalizer
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.database.entity.ActivityDayEntity
import app.tasker.core.database.entity.ContextSnapshotEntity
import app.tasker.core.database.entity.DayPlanEntity
import app.tasker.core.database.entity.DayPlanItemEntity
import app.tasker.core.database.entity.EventEntity
import app.tasker.core.database.entity.MaintenanceStateEntity
import app.tasker.core.database.entity.ProjectEntity
import app.tasker.core.database.entity.ReviewCardEntity
import app.tasker.core.database.entity.ReviewSessionEntity
import app.tasker.core.database.entity.SourceEntity
import app.tasker.core.database.entity.TagEntity
import app.tasker.core.database.entity.TaskEntity
import app.tasker.core.database.entity.TaskFtsEntity
import app.tasker.core.database.entity.TaskTagEntity
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.Actor
import app.tasker.core.model.ArchiveReason
import app.tasker.core.model.Bucket
import app.tasker.core.model.CandidateGroup
import app.tasker.core.model.CaptureChannel
import app.tasker.core.model.EnrichState
import app.tasker.core.model.EntityType
import app.tasker.core.model.Estimate
import app.tasker.core.model.EventType
import app.tasker.core.model.FieldSource
import app.tasker.core.model.PlanItemOrigin
import app.tasker.core.model.PlanItemOutcome
import app.tasker.core.model.PlanState
import app.tasker.core.model.ProjectStatus
import app.tasker.core.model.ReasonCode
import app.tasker.core.model.ReminderOffsets
import app.tasker.core.model.ReviewDecision
import app.tasker.core.model.ReviewKind
import app.tasker.core.model.SnapshotInputKind
import app.tasker.core.model.SourceKind
import app.tasker.core.model.TaskField
import app.tasker.core.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Full export and import of the user's data (DATA-1, tech plan §16); the base of backups and restore. */
@Singleton
class DataExporter @Inject constructor(
    private val db: TaskerDatabase,
    private val settings: SettingsRepository,
    private val clock: DayClock,
) {
    suspend fun export(): ExportBundle = db.withTransaction {
        val taskDao = db.taskDao()
        val contentDao = db.contentDao()
        val planDao = db.planDao()
        val serviceDao = db.serviceDao()
        ExportBundle(
            exportedAt = clock.now().toString(),
            dbVersion = TaskerDatabase.VERSION,
            settings = settings.current(),
            tables = ExportTables(
                task = taskDao.all().sortedBy { it.createdAt }.map { it.toRow() },
                project = db.projectDao().all().sortedBy { it.createdAt }.map { it.toRow() },
                tag = taskDao.allTags().map { TagRow(it.id, it.name, it.nameNorm) },
                taskTag = taskDao.allTaskTags().map { TaskTagRow(it.taskId, it.tagId) },
                contextSnapshot = contentDao.allSnapshots().map { it.toRow() },
                source = contentDao.allSources().map { it.toRow() },
                dayPlan = planDao.allPlans().sortedBy { it.date }.map { it.toRow() },
                dayPlanItem = planDao.allItems().map { it.toRow() },
                event = db.eventDao().all().map { it.toRow() },
                reviewSession = serviceDao.allSessions().map { it.toRow() },
                reviewCard = serviceDao.allCards().map { it.toRow() },
                activityDay = serviceDao.allActivityDays().map { ActivityDayRow(day(it.day), instant(it.firstSeenAt)) },
                maintenanceState = serviceDao.allMarks().map { MaintenanceRow(it.key, it.value) },
            ),
        )
    }

    fun encode(bundle: ExportBundle): String = DataJson.encodeToString(ExportBundle.serializer(), bundle)

    fun decode(text: String): ExportBundle {
        val bundle = DataJson.decodeFromString(ExportBundle.serializer(), text)
        require(bundle.format == ExportFormat.NAME) { "Not a Tasker export" }
        require(bundle.formatVersion <= ExportFormat.VERSION) { "The export was made by a newer version of the app" }
        return bundle
    }

    /** True when the database has no tasks and no projects (first start, restore prompt §16). */
    suspend fun isEmpty(): Boolean = db.taskDao().count() == 0 && db.projectDao().all().isEmpty()

    /**
     * Replaces all data with [bundle]: tables are cleared and filled in one go, the search index is rebuilt, settings are
     * restored. Used for restore from a backup and for importing an export file into an empty database.
     */
    suspend fun import(bundle: ExportBundle) {
        db.clearAllTables()
        db.withTransaction {
            val t = bundle.tables
            val taskDao = db.taskDao()
            val contentDao = db.contentDao()
            val planDao = db.planDao()
            val serviceDao = db.serviceDao()
            t.project.forEach { db.projectDao().insert(it.toEntity()) }
            t.task.forEach { taskDao.insert(it.toEntity()) }
            t.tag.forEach { taskDao.insertTag(TagEntity(it.id, it.name, it.nameNorm)) }
            taskDao.insertTaskTags(t.taskTag.map { TaskTagEntity(it.taskId, it.tagId) })
            t.contextSnapshot.forEach { contentDao.insertSnapshot(it.toEntity()) }
            t.source.forEach { contentDao.insertSource(it.toEntity()) }
            t.dayPlan.forEach { planDao.upsertPlan(it.toEntity()) }
            planDao.upsertItems(t.dayPlanItem.map { it.toEntity() })
            t.event.chunked(EVENT_CHUNK).forEach { chunk -> db.eventDao().insert(chunk.map { it.toEntity() }) }
            t.reviewSession.forEach { serviceDao.upsertSession(it.toEntity()) }
            t.reviewCard.forEach { serviceDao.insertCard(it.toEntity()) }
            t.activityDay.forEach { serviceDao.insertActivityDay(ActivityDayEntity(epochDay(it.day), millis(it.firstSeenAt))) }
            t.maintenanceState.forEach { serviceDao.setMark(MaintenanceStateEntity(it.key, it.value)) }
            rebuildSearchIndex()
        }
        settings.replace(bundle.settings)
    }

    /** Rebuilds the FTS index from tasks, notes, snapshots and tags (also after a normalizer change). */
    suspend fun rebuildSearchIndex() {
        val searchDao = db.searchDao()
        searchDao.clear()
        val snapshots = db.contentDao().allSnapshots().groupBy { it.taskId }
        val tags = db.taskDao().allTags().associateBy { it.id }
        val taskTags = db.taskDao().allTaskTags().groupBy({ it.taskId }, { tags[it.tagId]?.name.orEmpty() })
        db.taskDao().all().forEach { task ->
            searchDao.insert(
                TaskFtsEntity(
                    taskId = task.id,
                    title = SearchNormalizer.indexText(task.title),
                    note = SearchNormalizer.indexText(task.note),
                    snapshots = SearchNormalizer.indexText(
                        snapshots[task.id].orEmpty().joinToString(" ") { listOfNotNull(it.text, it.nextStep).joinToString(" ") },
                    ),
                    tags = SearchNormalizer.indexText(taskTags[task.id].orEmpty().joinToString(" ")),
                ),
            )
        }
    }

    private companion object {
        const val EVENT_CHUNK = 500
        val params = MapSerializer(String.serializer(), String.serializer())

        fun day(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()

        fun epochDay(text: String): Long = LocalDate.parse(text).toEpochDay()

        fun instant(millis: Long): String = Instant.ofEpochMilli(millis).toString()

        fun millis(text: String): Long = Instant.parse(text).toEpochMilli()

        fun TaskEntity.toRow() = TaskRow(
            id = id,
            title = title,
            rawInput = rawInput,
            note = note,
            status = status.name,
            bucket = bucket?.name,
            position = position,
            deadlineDate = deadlineDate?.let(::day),
            deadlineTime = deadlineTime?.let { LocalTime.ofSecondOfDay(it * 60L).toString() },
            deadlineZone = deadlineZone,
            deadlineAt = deadlineAt?.let(::instant),
            planDate = planDate?.let(::day),
            estimate = estimate?.name,
            projectId = projectId,
            postponeCount = postponeCount,
            postponePromptedAt = postponePromptedAt,
            planDateRolled = planDateRolled?.let(::day),
            lastPostponeDay = lastPostponeDay?.let(::day),
            carryOverSince = carryOverSince?.let(::day),
            reminderOffsets = decodeReminders(reminderOffsets)?.minutesBefore,
            lastTouchedAt = instant(lastTouchedAt),
            inReview = inReview,
            reviewSince = reviewSince?.let(::instant),
            reviewSkipStreak = reviewSkipStreak,
            offPlan = offPlan,
            captureChannel = captureChannel.name,
            fieldSources = decodeFieldSources(fieldSources).entries.associate { it.key.name to it.value.name },
            enrichState = enrichState.name,
            createdAt = instant(createdAt),
            completedAt = completedAt?.let(::instant),
            startedAt = startedAt?.let(::instant),
            archivedAt = archivedAt?.let(::instant),
            archiveReason = archiveReason?.name,
            updatedAt = instant(updatedAt),
        )

        fun TaskRow.toEntity() = TaskEntity(
            id = id,
            title = title,
            rawInput = rawInput,
            note = note,
            status = TaskStatus.valueOf(status),
            bucket = bucket?.let(Bucket::valueOf),
            position = position,
            deadlineDate = deadlineDate?.let(::epochDay),
            deadlineTime = deadlineTime?.let { LocalTime.parse(it).toSecondOfDay() / 60 },
            deadlineZone = deadlineZone,
            deadlineAt = deadlineAt?.let(::millis),
            planDate = planDate?.let(::epochDay),
            estimate = estimate?.let(Estimate::valueOf),
            projectId = projectId,
            postponeCount = postponeCount,
            postponePromptedAt = postponePromptedAt,
            planDateRolled = planDateRolled?.let(::epochDay),
            lastPostponeDay = lastPostponeDay?.let(::epochDay),
            carryOverSince = carryOverSince?.let(::epochDay),
            reminderOffsets = encodeReminders(reminderOffsets?.let { ReminderOffsets(it) }),
            lastTouchedAt = millis(lastTouchedAt),
            inReview = inReview,
            reviewSince = reviewSince?.let(::millis),
            reviewSkipStreak = reviewSkipStreak,
            offPlan = offPlan,
            captureChannel = CaptureChannel.valueOf(captureChannel),
            fieldSources = encodeFieldSources(
                fieldSources.entries.mapNotNull { (field, source) ->
                    val f = TaskField.entries.firstOrNull { it.name == field }
                    val s = FieldSource.entries.firstOrNull { it.name == source }
                    if (f != null && s != null) f to s else null
                }.toMap(),
            ),
            enrichState = EnrichState.valueOf(enrichState),
            createdAt = millis(createdAt),
            completedAt = completedAt?.let(::millis),
            startedAt = startedAt?.let(::millis),
            archivedAt = archivedAt?.let(::millis),
            archiveReason = archiveReason?.let(ArchiveReason::valueOf),
            updatedAt = millis(updatedAt),
        )

        fun ProjectEntity.toRow() = ProjectRow(
            id = id,
            name = name,
            outcome = outcome,
            status = status.name,
            position = position,
            lastActivityAt = instant(lastActivityAt),
            inReview = inReview,
            reviewSince = reviewSince?.let(::instant),
            createdAt = instant(createdAt),
            completedAt = completedAt?.let(::instant),
            archivedAt = archivedAt?.let(::instant),
            updatedAt = instant(updatedAt),
        )

        fun ProjectRow.toEntity() = ProjectEntity(
            id = id,
            name = name,
            outcome = outcome,
            status = ProjectStatus.valueOf(status),
            position = position,
            lastActivityAt = millis(lastActivityAt),
            inReview = inReview,
            reviewSince = reviewSince?.let(::millis),
            createdAt = millis(createdAt),
            completedAt = completedAt?.let(::millis),
            archivedAt = archivedAt?.let(::millis),
            updatedAt = millis(updatedAt),
        )

        fun ContextSnapshotEntity.toRow() = SnapshotRow(id, taskId, text, inputKind.name, nextStep, instant(createdAt))

        fun SnapshotRow.toEntity() =
            ContextSnapshotEntity(id, taskId, text, SnapshotInputKind.valueOf(inputKind), nextStep, millis(createdAt))

        fun SourceEntity.toRow() = SourceRow(
            id = id,
            taskId = taskId,
            kind = kind.name,
            url = url,
            externalId = externalId,
            appPackage = appPackage,
            titleSnapshot = titleSnapshot,
            summary = summary,
            externalState = externalState,
            isActive = isActive,
            lastSyncedAt = lastSyncedAt?.let(::instant),
        )

        fun SourceRow.toEntity() = SourceEntity(
            id = id,
            taskId = taskId,
            kind = SourceKind.valueOf(kind),
            url = url,
            externalId = externalId,
            appPackage = appPackage,
            titleSnapshot = titleSnapshot,
            summary = summary,
            externalState = externalState,
            isActive = isActive,
            lastSyncedAt = lastSyncedAt?.let(::millis),
        )

        fun DayPlanEntity.toRow() = DayPlanRow(
            date = day(date),
            state = state.name,
            createdAt = instant(createdAt),
            acceptedAt = acceptedAt?.let(::instant),
            workingMin = workingMin,
            busyMin = busyMin,
            capacityMin = capacityMin,
            plannedMin = plannedMin,
        )

        fun DayPlanRow.toEntity() = DayPlanEntity(
            date = epochDay(date),
            state = PlanState.valueOf(state),
            createdAt = millis(createdAt),
            acceptedAt = acceptedAt?.let(::millis),
            workingMin = workingMin,
            busyMin = busyMin,
            capacityMin = capacityMin,
            plannedMin = plannedMin,
        )

        fun DayPlanItemEntity.toRow() = DayPlanItemRow(
            day(date),
            taskId,
            position,
            minutes,
            origin.name,
            candidateGroup?.name,
            outcome.name,
        )

        fun DayPlanItemRow.toEntity() = DayPlanItemEntity(
            date = epochDay(date),
            taskId = taskId,
            position = position,
            minutes = minutes,
            origin = PlanItemOrigin.valueOf(origin),
            candidateGroup = candidateGroup?.let(CandidateGroup::valueOf),
            outcome = PlanItemOutcome.valueOf(outcome),
        )

        fun EventEntity.toRow() = EventRow(
            id = id,
            batchId = batchId,
            entityType = entityType.name,
            entityId = entityId,
            actor = actor.name,
            type = type.name,
            changes = DataJson.parseToJsonElement(changes),
            reasonCode = reasonCode?.name,
            reasonParams = reasonParams?.let { DataJson.decodeFromString(params, it) },
            createdAt = instant(createdAt),
            undoUntil = undoUntil?.let(::instant),
            undoneAt = undoneAt?.let(::instant),
            undoneBy = undoneBy,
        )

        fun EventRow.toEntity() = EventEntity(
            id = id,
            batchId = batchId,
            entityType = EntityType.valueOf(entityType),
            entityId = entityId,
            actor = Actor.valueOf(actor),
            type = EventType.valueOf(type),
            changes = changes.toString(),
            reasonCode = reasonCode?.let(ReasonCode::valueOf),
            reasonParams = reasonParams?.let { DataJson.encodeToString(params, it) },
            createdAt = millis(createdAt),
            undoUntil = undoUntil?.let(::millis),
            undoneAt = undoneAt?.let(::millis),
            undoneBy = undoneBy,
        )

        fun ReviewSessionEntity.toRow() = ReviewSessionRow(id, kind.name, day(logicalDay), instant(startedAt), endedAt?.let(::instant))

        fun ReviewSessionRow.toEntity() =
            ReviewSessionEntity(id, ReviewKind.valueOf(kind), epochDay(logicalDay), millis(startedAt), endedAt?.let(::millis))

        fun ReviewCardEntity.toRow() = ReviewCardRow(
            id = id,
            sessionId = sessionId,
            kind = kind.name,
            entityType = entityType.name,
            entityId = entityId,
            logicalDay = day(logicalDay),
            shownAt = instant(shownAt),
            decision = decision?.name,
            decidedAt = decidedAt?.let(::instant),
        )

        fun ReviewCardRow.toEntity() = ReviewCardEntity(
            id = id,
            sessionId = sessionId,
            kind = ReviewKind.valueOf(kind),
            entityType = EntityType.valueOf(entityType),
            entityId = entityId,
            logicalDay = epochDay(logicalDay),
            shownAt = millis(shownAt),
            decision = decision?.let(ReviewDecision::valueOf),
            decidedAt = decidedAt?.let(::millis),
        )
    }
}
