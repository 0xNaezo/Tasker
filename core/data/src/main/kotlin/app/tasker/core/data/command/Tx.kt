package app.tasker.core.data.command

import app.tasker.core.data.effects.CommitInfo
import app.tasker.core.data.mapper.toEntity
import app.tasker.core.data.mapper.toEpochDayLong
import app.tasker.core.data.mapper.toModel
import app.tasker.core.data.search.SearchNormalizer
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.database.entity.TagEntity
import app.tasker.core.database.entity.TaskFtsEntity
import app.tasker.core.database.entity.TaskTagEntity
import app.tasker.core.domain.history.ColumnKind
import app.tasker.core.domain.history.ProjectColumns
import app.tasker.core.domain.history.TaskColumns
import app.tasker.core.domain.order.Positions
import app.tasker.core.model.Actor
import app.tasker.core.model.AppSettings
import app.tasker.core.model.BatchId
import app.tasker.core.model.Bucket
import app.tasker.core.model.ContextSnapshot
import app.tasker.core.model.DayPlan
import app.tasker.core.model.DayPlanItem
import app.tasker.core.model.EnrichState
import app.tasker.core.model.EntityType
import app.tasker.core.model.Event
import app.tasker.core.model.EventType
import app.tasker.core.model.FieldChange
import app.tasker.core.model.PlanItemOrigin
import app.tasker.core.model.PlanItemOutcome
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectId
import app.tasker.core.model.Reason
import app.tasker.core.model.Source
import app.tasker.core.model.Task
import app.tasker.core.model.TaskId
import app.tasker.core.model.TaskStatus
import app.tasker.core.model.UuidV7
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive

private class TrackedTask(val original: Task?, var current: Task, val type: EventType, var reason: Reason?) {
    val extraChanges = ArrayList<FieldChange>()
    var searchDirty = original == null
}

private class TrackedProject(val original: Project?, var current: Project, val type: EventType, var reason: Reason?)

private class TrackedPlan(val original: DayPlan?, var current: DayPlan?)

/**
 * One command = one transaction = one batch (tech plan §4.2, §7.5). Every change goes through this class, which
 * - enforces the touch invariant: only user commands move `last_touched_at`, rules, AI and integrations never do
 *   (TTL-1); a user touch also takes the task out of review and counts as project activity;
 * - writes one event per changed entity with field-level changes, mandatory reasons for automation and an undo window;
 * - keeps tags, the search index and today's plan in step with task changes.
 */
class Tx internal constructor(
    val batchId: BatchId,
    val actor: Actor,
    val now: Instant,
    val today: LocalDate,
    val settings: AppSettings,
    val zone: ZoneId,
    private val db: TaskerDatabase,
) {
    val isUser: Boolean get() = actor == Actor.USER

    private val taskDao = db.taskDao()
    private val projectDao = db.projectDao()
    private val planDao = db.planDao()
    private val contentDao = db.contentDao()

    private val tasks = LinkedHashMap<TaskId, TrackedTask>()
    private val loadedTasks = HashMap<TaskId, Task>()
    private val projects = LinkedHashMap<ProjectId, TrackedProject>()
    private val loadedProjects = HashMap<ProjectId, Project>()
    private val plans = LinkedHashMap<LocalDate, TrackedPlan>()
    private val deleted = LinkedHashSet<TaskId>()
    private val reminderTasks = LinkedHashSet<TaskId>()
    private val enrichTasks = LinkedHashSet<TaskId>()
    private val touchedProjects = HashSet<ProjectId>()

    /** New time-ordered identifier for an entity created in this command. */
    fun newId(): String = UuidV7.generate(now.toEpochMilli())

    // region Tasks

    suspend fun task(id: TaskId): Task = taskOrNull(id) ?: throw TaskNotFoundException(id)

    suspend fun taskOrNull(id: TaskId): Task? {
        if (id in deleted) return null
        tasks[id]?.let { return it.current }
        loadedTasks[id]?.let { return it }
        return taskDao.getWithTags(id)?.toModel()?.also { loadedTasks[id] = it }
    }

    suspend fun tasks(ids: Collection<TaskId>): List<Task> {
        preload(ids)
        return ids.mapNotNull { taskOrNull(it) }
    }

    /** Loads tasks with their tags in chunks, so rules over thousands of tasks stay within the time budget (§11.1). */
    suspend fun preload(ids: Collection<TaskId>) {
        val missing = ids.filter { it !in tasks && it !in loadedTasks && it !in deleted }.distinct()
        for (chunk in missing.chunked(PRELOAD_CHUNK)) {
            taskDao.getWithTags(chunk).forEach { loadedTasks[it.task.id] = it.toModel() }
        }
    }

    /** Creates a task (CREATED event). Captures by the user count as a touch. */
    suspend fun insertTask(task: Task, reason: Reason? = null): Task {
        val created = canonical(task).copy(
            lastTouchedAt = if (isUser) now else task.lastTouchedAt,
            updatedAt = now,
        )
        taskDao.insert(created.toEntity(zone))
        writeTags(created.id, created.tags)
        val stored = created.copy(tags = resolvedTags(created.id))
        tasks[created.id] = TrackedTask(null, stored, EventType.CREATED, reason)
        if (stored.deadline != null) reminderTasks += stored.id
        if (stored.enrichState == EnrichState.PENDING) enrichTasks += stored.id
        stored.projectId?.let { if (isUser) touchProject(it) }
        lateAddIfNeeded(null, stored)
        return stored
    }

    /**
     * Saves [after] as the new state of the task. For user commands [touch] (default) moves `last_touched_at`, takes
     * the task out of review and marks project activity. For any other actor `last_touched_at` is kept as it was.
     */
    suspend fun updateTask(
        after: Task,
        type: EventType = EventType.UPDATED,
        reason: Reason? = null,
        touch: Boolean = isUser,
        keepReview: Boolean = false,
    ): Task {
        val before = task(after.id)
        var next = canonical(after)
        if (isUser && touch) {
            next = next.copy(lastTouchedAt = now)
            if (next.inReview && !keepReview) next = next.copy(inReview = false, reviewSince = null, reviewSkipStreak = 0)
        } else {
            next = next.copy(lastTouchedAt = before.lastTouchedAt)
        }
        if (next.copy(updatedAt = before.updatedAt) == before) return before
        next = next.copy(updatedAt = now)
        if (next.tags != before.tags) {
            writeTags(next.id, next.tags)
            next = next.copy(tags = resolvedTags(next.id))
        }
        taskDao.update(next.toEntity(zone))
        track(before, next, type, reason)
        if (isUser && touch) {
            setOfNotNull(before.projectId, next.projectId).forEach { touchProject(it) }
        }
        syncPlanWithStatus(before, next)
        lateAddIfNeeded(before, next)
        return next
    }

    private fun track(before: Task, next: Task, type: EventType, reason: Reason?) {
        val tracked = tasks[next.id]
        if (tracked == null) {
            tasks[next.id] = TrackedTask(before, next, type, reason)
            loadedTasks.remove(next.id)
        } else {
            tracked.current = next
            if (tracked.reason == null) tracked.reason = reason
        }
        val t = tasks.getValue(next.id)
        if (before.title != next.title || before.note != next.note || before.tags != next.tags) t.searchDirty = true
        if (before.deadline != next.deadline || before.reminderOffsets != next.reminderOffsets ||
            before.status != next.status
        ) {
            reminderTasks += next.id
        }
        if (next.enrichState == EnrichState.PENDING && before.enrichState != EnrichState.PENDING) enrichTasks += next.id
    }

    /** Position after the last active task of [bucket] (manual order, DAT-5). */
    suspend fun nextPosition(bucket: Bucket?): Long = Positions.afterLast(taskDao.maxPosition(bucket?.name))

    /** Position after the last project. */
    suspend fun nextProjectPosition(): Long = Positions.afterLast(projectDao.maxPosition())

    /** Positions of the active tasks of [bucket] in their manual order. */
    suspend fun bucketOrder(bucket: Bucket?): List<Pair<TaskId, Long>> = taskDao.bucketTasks(bucket?.name)
        .map { entity -> entity.id to (tasks[entity.id]?.current?.position ?: entity.position) }
        .sortedBy { it.second }

    /**
     * Rewrites positions of [ids] to evenly spaced values without events or touches: rebalancing is bookkeeping,
     * not a user change of those tasks (tech plan §7.2).
     */
    suspend fun rebalance(ids: List<TaskId>): Map<TaskId, Long> {
        val positions = Positions.rebalanced(ids.size)
        return ids.zip(positions).toMap().onEach { (id, position) ->
            taskDao.setPosition(id, position)
            loadedTasks.remove(id)
            tasks[id]?.let { tracked -> tracked.current = tracked.current.copy(position = position) }
        }
    }

    /** Adds a context snapshot (EXC-3, EXC-4); shown in the task history as part of this command's event. */
    suspend fun addSnapshot(snapshot: ContextSnapshot) {
        val task = task(snapshot.taskId)
        contentDao.insertSnapshot(snapshot.toEntity())
        val tracked = tasks.getOrPut(task.id) {
            loadedTasks.remove(task.id)
            TrackedTask(task, task, EventType.SNAPSHOT_ADDED, null)
        }
        tracked.extraChanges += FieldChange(SNAPSHOT_FIELD, null, JsonPrimitive(snapshot.text))
        tracked.searchDirty = true
    }

    suspend fun addSource(source: Source) {
        val task = task(source.taskId)
        contentDao.insertSource(source.toEntity())
        val tracked = tasks.getOrPut(task.id) {
            loadedTasks.remove(task.id)
            TrackedTask(task, task, EventType.UPDATED, null)
        }
        tracked.extraChanges += FieldChange(SOURCE_FIELD, null, JsonPrimitive(source.url ?: source.appPackage ?: source.kind.name))
    }

    /** Permanent deletion (ARC-1): the task, its snapshots, sources, plan items, index and history. */
    suspend fun deleteTask(id: TaskId) {
        task(id)
        db.searchDao().delete(id)
        db.eventDao().deleteForEntity(id)
        db.serviceDao().deletePendingForTask(id)
        taskDao.delete(id)
        taskDao.deleteUnusedTags()
        tasks.remove(id)
        loadedTasks.remove(id)
        plans.values.forEach { tracked -> tracked.current = tracked.current?.withoutItem(id) }
        deleted += id
    }

    // endregion

    // region Projects

    suspend fun project(id: ProjectId): Project = projectOrNull(id) ?: throw ProjectNotFoundException(id)

    suspend fun projectOrNull(id: ProjectId): Project? {
        projects[id]?.let { return it.current }
        loadedProjects[id]?.let { return it }
        return projectDao.get(id)?.toModel()?.also { loadedProjects[id] = it }
    }

    suspend fun insertProject(project: Project, reason: Reason? = null): Project {
        val created = project.copy(updatedAt = now)
        projectDao.insert(created.toEntity())
        projects[created.id] = TrackedProject(null, created, EventType.CREATED, reason)
        touchedProjects += created.id
        return created
    }

    /** Saves a project. A user edit is project activity and takes it out of review; automation never moves activity. */
    suspend fun updateProject(
        after: Project,
        type: EventType = EventType.UPDATED,
        reason: Reason? = null,
        touch: Boolean = isUser,
        keepReview: Boolean = false,
    ): Project {
        val before = project(after.id)
        var next = after
        next = if (isUser && touch) {
            touchedProjects += next.id
            val active = next.copy(lastActivityAt = maxOf(now, before.lastActivityAt))
            if (keepReview) active else active.copy(inReview = false, reviewSince = null)
        } else {
            next.copy(lastActivityAt = before.lastActivityAt)
        }
        if (next.copy(updatedAt = before.updatedAt) == before) return before
        next = next.copy(updatedAt = now)
        projectDao.update(next.toEntity())
        val tracked = projects[next.id]
        if (tracked == null) {
            projects[next.id] = TrackedProject(before, next, type, reason)
            loadedProjects.remove(next.id)
        } else {
            tracked.current = next
            if (tracked.reason == null) tracked.reason = reason
        }
        return next
    }

    /** Touching or completing a task of a project is activity of the project (tech plan §7.3). */
    private suspend fun touchProject(id: ProjectId) {
        if (id in touchedProjects) return
        val project = projectOrNull(id) ?: return
        updateProject(project, EventType.UPDATED, touch = true)
    }

    // endregion

    // region Plans

    suspend fun plan(date: LocalDate): DayPlan? {
        plans[date]?.let { return it.current }
        val key = date.toEpochDayLong()
        val entity = planDao.plan(key)
        val plan = entity?.toModel(planDao.items(key))
        plans[date] = TrackedPlan(plan, plan)
        return plan
    }

    suspend fun savePlan(plan: DayPlan) {
        val existing = plan(plan.date)
        val normalized = plan.copy(items = plan.items.sortedBy { it.position })
        if (existing == normalized) return
        val key = plan.date.toEpochDayLong()
        planDao.upsertPlan(normalized.toEntity())
        planDao.replaceItems(key, normalized.items.map { it.toEntity() })
        plans.getValue(plan.date).current = normalized
    }

    /** Removes the task from today's plan (postpone, move to another horizon): the item becomes REMOVED. */
    suspend fun removeFromTodaysPlan(taskId: TaskId) {
        val plan = plan(today) ?: return
        val item = plan.item(taskId) ?: return
        if (item.outcome != PlanItemOutcome.PENDING) return
        savePlan(plan.withItem(item.copy(outcome = PlanItemOutcome.REMOVED)))
    }

    /** Done → DONE, archived → REMOVED, reopened → PENDING again; only today's plan follows task status. */
    private suspend fun syncPlanWithStatus(before: Task, after: Task) {
        if (before.status == after.status) return
        val plan = plan(today) ?: return
        val item = plan.item(after.id) ?: return
        val outcome = when {
            after.status == TaskStatus.DONE && item.outcome == PlanItemOutcome.PENDING -> PlanItemOutcome.DONE
            after.status == TaskStatus.ARCHIVED && item.outcome == PlanItemOutcome.PENDING -> PlanItemOutcome.REMOVED
            before.status == TaskStatus.DONE && after.status.isActive && item.outcome == PlanItemOutcome.DONE ->
                PlanItemOutcome.PENDING
            else -> return
        }
        savePlan(plan.withItem(item.copy(outcome = outcome)))
    }

    /** After acceptance, tasks the user sends to today are appended to the plan (tech plan §10.5, step 5). */
    private suspend fun lateAddIfNeeded(before: Task?, after: Task) {
        if (!isUser || !after.status.isActive) return
        if (isForTodayByFields(before) || !isForTodayByFields(after)) return
        val plan = plan(today)?.takeIf { it.isAccepted } ?: return
        val existing = plan.item(after.id)
        val item = when {
            existing == null -> DayPlanItem(
                date = today,
                taskId = after.id,
                position = plan.nextPosition(),
                minutes = settings.estimateMinutes.of(after.estimate),
                origin = PlanItemOrigin.LATE_ADD,
            )
            existing.outcome == PlanItemOutcome.REMOVED -> existing.copy(outcome = PlanItemOutcome.PENDING)
            else -> return
        }
        savePlan(plan.withItem(item))
    }

    fun isForTodayByFields(task: Task?): Boolean {
        if (task == null) return false
        val planDate = task.planDate
        return task.bucket == Bucket.TODAY || (planDate != null && !planDate.isAfter(today))
    }

    // endregion

    // region Commit

    internal suspend fun flush(): CommitInfo {
        val events = ArrayList<Event>()
        val undoUntil = now.plus(if (isUser) USER_UNDO_WINDOW else AUTOMATION_UNDO_WINDOW)
        for ((id, tracked) in tasks) {
            if (id in deleted) continue
            val changes = TaskColumns.diff(tracked.original ?: blankTask(tracked.current), tracked.current) + tracked.extraChanges
            if (tracked.searchDirty) reindex(tracked.current)
            if (changes.isEmpty()) continue
            if (tracked.original != null && changes.all { TaskColumns.byKey(it.field)?.kind == ColumnKind.MARKER }) continue
            events += event(EntityType.TASK, id, tracked.type, changes, tracked.reason, undoUntil)
        }
        for ((id, tracked) in projects) {
            val changes = ProjectColumns.diff(tracked.original ?: blankProject(tracked.current), tracked.current)
            if (changes.isEmpty()) continue
            if (tracked.original != null && changes.all { ProjectColumns.byKey(it.field)?.kind == ColumnKind.MARKER }) continue
            events += event(EntityType.PROJECT, id, tracked.type, changes, tracked.reason, undoUntil)
        }
        if (isUser) {
            for ((date, tracked) in plans) {
                val changes = planChanges(tracked.original, tracked.current)
                if (changes.isNotEmpty()) {
                    events += event(EntityType.PLAN, date.toString(), EventType.PLAN_CHANGED, changes, null, undoUntil)
                }
            }
        }
        if (events.isNotEmpty()) db.eventDao().insert(events.map { it.toEntity() })
        return CommitInfo(
            batchId = batchId,
            actor = actor,
            taskIds = tasks.keys - deleted,
            projectIds = projects.keys.toSet(),
            planDates = plans.filterValues { it.original != it.current }.keys,
            reminderTaskIds = reminderTasks - deleted,
            enrichTaskIds = enrichTasks - deleted,
            deletedTaskIds = deleted.toSet(),
        ).also { eventsWritten = events.size }
    }

    internal var eventsWritten: Int = 0
        private set

    private fun event(
        type: EntityType,
        entityId: String,
        eventType: EventType,
        changes: List<FieldChange>,
        reason: Reason?,
        undoUntil: Instant,
    ) = Event(
        id = newId(),
        batchId = batchId,
        entityType = type,
        entityId = entityId,
        actor = actor,
        type = eventType,
        changes = changes,
        reason = reason,
        createdAt = now,
        undoUntil = undoUntil,
    )

    private fun planChanges(original: DayPlan?, current: DayPlan?): List<FieldChange> {
        if (original == current || current == null) return emptyList()
        val result = ArrayList<FieldChange>()
        if (original?.state != current.state) {
            result += FieldChange(
                PlanFields.STATE,
                original?.state?.let { PlanFields.stateCodec.encode(it) },
                PlanFields.stateCodec.encode(current.state),
            )
        }
        val before = original?.items.orEmpty().associateBy { it.taskId }
        val after = current.items.associateBy { it.taskId }
        for (taskId in (before.keys + after.keys)) {
            val old = before[taskId]
            val new = after[taskId]
            if (old != new) result += FieldChange(PlanFields.itemKey(taskId), PlanFields.encode(old), PlanFields.encode(new))
        }
        return result
    }

    private suspend fun reindex(task: Task) {
        val snapshots = contentDao.snapshots(task.id).joinToString(" ") { listOfNotNull(it.text, it.nextStep).joinToString(" ") }
        db.searchDao().replace(
            TaskFtsEntity(
                taskId = task.id,
                title = SearchNormalizer.indexText(task.title),
                note = SearchNormalizer.indexText(task.note),
                snapshots = SearchNormalizer.indexText(snapshots),
                tags = SearchNormalizer.indexText(task.tags.joinToString(" ")),
            ),
        )
    }

    // endregion

    // region Canonical form

    /**
     * Stored form of a task: timestamps in milliseconds, timed deadlines with a zone and minute precision, tags
     * without duplicates, the logical-day fields as they are.
     */
    private fun canonical(task: Task): Task {
        val deadline = task.deadline?.let { d ->
            val time = d.time?.truncatedTo(ChronoUnit.MINUTES)
            d.copy(time = time, zone = if (time != null) d.zone ?: zone else null)
        }
        return task.copy(
            title = task.title.trim(),
            note = task.note?.trim()?.ifEmpty { null },
            deadline = deadline,
            tags = task.tags.map { it.trim().removePrefix("@") }.filter { it.isNotEmpty() }
                .distinctBy(::normalizeTag)
                .sortedBy(::normalizeTag),
            lastTouchedAt = task.lastTouchedAt.truncatedTo(ChronoUnit.MILLIS),
            createdAt = task.createdAt.truncatedTo(ChronoUnit.MILLIS),
            completedAt = task.completedAt?.truncatedTo(ChronoUnit.MILLIS),
            startedAt = task.startedAt?.truncatedTo(ChronoUnit.MILLIS),
            archivedAt = task.archivedAt?.truncatedTo(ChronoUnit.MILLIS),
            reviewSince = task.reviewSince?.truncatedTo(ChronoUnit.MILLIS),
            updatedAt = task.updatedAt.truncatedTo(ChronoUnit.MILLIS),
        )
    }

    private suspend fun writeTags(taskId: TaskId, names: List<String>) {
        taskDao.clearTaskTags(taskId)
        if (names.isEmpty()) {
            taskDao.deleteUnusedTags()
            return
        }
        val links = names.map { name ->
            val norm = normalizeTag(name)
            val tag = taskDao.tagByNorm(norm) ?: TagEntity(newId(), name, norm).also { taskDao.insertTag(it) }
            TaskTagEntity(taskId, tag.id)
        }
        taskDao.insertTaskTags(links)
        taskDao.deleteUnusedTags()
    }

    private suspend fun resolvedTags(taskId: TaskId): List<String> =
        taskDao.getWithTags(taskId)?.toModel()?.tags.orEmpty()

    // endregion

    companion object {
        /** Snackbar undo of a user command (EXC-7); generous because accessibility may extend snackbar timeouts. */
        val USER_UNDO_WINDOW: Duration = Duration.ofMinutes(15)

        /** Automation can be undone for 30 days (AUT-1). */
        val AUTOMATION_UNDO_WINDOW: Duration = Duration.ofDays(30)

        const val SNAPSHOT_FIELD = "snapshot"
        private const val PRELOAD_CHUNK = 500
        const val SOURCE_FIELD = "source"

        fun normalizeTag(name: String): String = name.trim().removePrefix("@").lowercase(Locale.ROOT).replace('ё', 'е')

        private fun blankTask(task: Task) = Task(
            id = task.id,
            title = "",
            lastTouchedAt = task.createdAt,
            createdAt = task.createdAt,
            captureChannel = task.captureChannel,
            updatedAt = task.updatedAt,
        )

        private fun blankProject(project: Project) = Project(
            id = project.id,
            name = "",
            lastActivityAt = project.lastActivityAt,
            createdAt = project.createdAt,
        )
    }
}
