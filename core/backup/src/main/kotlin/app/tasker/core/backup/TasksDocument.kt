package app.tasker.core.backup

import app.tasker.core.data.export.ExportBundle
import app.tasker.core.data.export.ProjectRow
import app.tasker.core.data.export.SnapshotRow
import app.tasker.core.data.export.SourceRow
import app.tasker.core.data.export.TaskRow
import app.tasker.core.model.ArchiveReason
import app.tasker.core.model.Bucket
import app.tasker.core.model.ContextSnapshot
import app.tasker.core.model.Deadline
import app.tasker.core.model.Estimate
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectStatus
import app.tasker.core.model.SnapshotInputKind
import app.tasker.core.model.Source
import app.tasker.core.model.SourceKind
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Everything `tasks.md` shows, as domain models (DATA-1, tech plan §16). The export builds it from the same
 * [ExportBundle] as `export.json`, so both files of one export describe the same moment.
 *
 * @property zone the zone dates and times are shown in (the device zone at export time).
 */
data class TasksDocument(
    val exportedAt: Instant,
    val zone: ZoneId,
    val tasks: List<Task>,
    val projects: List<Project> = emptyList(),
    val snapshots: List<ContextSnapshot> = emptyList(),
    val sources: List<Source> = emptyList(),
) {
    internal companion object {
        /**
         * Maps export rows back to domain models. Enum names unknown to this version fall back to neutral values, so
         * rendering the Markdown never fails an export.
         */
        fun from(bundle: ExportBundle, zone: ZoneId): TasksDocument {
            val tables = bundle.tables
            val tags = tables.tag.associateBy { it.id }
            val tagsByTask = tables.taskTag
                .groupBy({ it.taskId }, { tags[it.tagId] })
                .mapValues { (_, rows) -> rows.filterNotNull().sortedBy { it.nameNorm }.map { it.name } }
            return TasksDocument(
                exportedAt = Instant.parse(bundle.exportedAt),
                zone = zone,
                tasks = tables.task.map { it.toModel(tagsByTask[it.id].orEmpty()) },
                projects = tables.project.map { it.toModel() },
                snapshots = tables.contextSnapshot.map { it.toModel() },
                sources = tables.source.map { it.toModel() },
            )
        }

        private fun TaskRow.toModel(tags: List<String>) = Task(
            id = id,
            title = title,
            rawInput = rawInput,
            note = note,
            status = enumOrNull<TaskStatus>(status) ?: TaskStatus.OPEN,
            bucket = bucket?.let { enumOrNull<Bucket>(it) },
            position = position,
            deadline = deadlineDate?.let { date ->
                val time = deadlineTime?.let(LocalTime::parse)
                Deadline(LocalDate.parse(date), time, if (time != null) deadlineZone?.let(::zoneOrNull) else null)
            },
            planDate = planDate?.let(LocalDate::parse),
            estimate = estimate?.let { enumOrNull<Estimate>(it) },
            projectId = projectId,
            tags = tags,
            postponeCount = postponeCount,
            lastTouchedAt = Instant.parse(lastTouchedAt),
            inReview = inReview,
            reviewSince = reviewSince?.let(Instant::parse),
            reviewSkipStreak = reviewSkipStreak,
            offPlan = offPlan,
            createdAt = Instant.parse(createdAt),
            completedAt = completedAt?.let(Instant::parse),
            startedAt = startedAt?.let(Instant::parse),
            archivedAt = archivedAt?.let(Instant::parse),
            archiveReason = archiveReason?.let { enumOrNull<ArchiveReason>(it) },
            updatedAt = Instant.parse(updatedAt),
        )

        private fun ProjectRow.toModel() = Project(
            id = id,
            name = name,
            outcome = outcome,
            status = enumOrNull<ProjectStatus>(status) ?: ProjectStatus.ACTIVE,
            position = position,
            lastActivityAt = Instant.parse(lastActivityAt),
            inReview = inReview,
            reviewSince = reviewSince?.let(Instant::parse),
            createdAt = Instant.parse(createdAt),
            completedAt = completedAt?.let(Instant::parse),
            archivedAt = archivedAt?.let(Instant::parse),
            updatedAt = Instant.parse(updatedAt),
        )

        private fun SnapshotRow.toModel() = ContextSnapshot(
            id = id,
            taskId = taskId,
            text = text,
            inputKind = enumOrNull<SnapshotInputKind>(inputKind) ?: SnapshotInputKind.TEXT,
            nextStep = nextStep,
            createdAt = Instant.parse(createdAt),
        )

        private fun SourceRow.toModel() = Source(
            id = id,
            taskId = taskId,
            kind = enumOrNull<SourceKind>(kind) ?: SourceKind.LINK,
            url = url,
            externalId = externalId,
            appPackage = appPackage,
            titleSnapshot = titleSnapshot,
            summary = summary,
            externalState = externalState,
            isActive = isActive,
            lastSyncedAt = lastSyncedAt?.let(Instant::parse),
        )

        private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? = enumValues<T>().firstOrNull { it.name == name }

        private fun zoneOrNull(id: String): ZoneId? = try {
            ZoneId.of(id)
        } catch (e: DateTimeException) {
            null
        }
    }
}
