package app.tasker.core.data.command

import app.tasker.core.database.TaskerDatabase
import app.tasker.core.domain.order.Positions
import app.tasker.core.domain.postpone.PostponeCounter
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.domain.postpone.PostponePolicy
import app.tasker.core.domain.provenance.FieldProvenance
import app.tasker.core.domain.split.SplitPlanner
import app.tasker.core.domain.status.TaskAction
import app.tasker.core.domain.status.TaskStateMachine
import app.tasker.core.model.ArchiveReason
import app.tasker.core.model.Bucket
import app.tasker.core.model.ContextSnapshot
import app.tasker.core.model.DayPlanItem
import app.tasker.core.model.Deadline
import app.tasker.core.model.EnrichState
import app.tasker.core.model.Estimate
import app.tasker.core.model.EventType
import app.tasker.core.model.FieldSource
import app.tasker.core.model.PlanItemOutcome
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectId
import app.tasker.core.model.ProjectStatus
import app.tasker.core.model.ReminderOffsets
import app.tasker.core.model.SnapshotInputKind
import app.tasker.core.model.Task
import app.tasker.core.model.TaskField
import app.tasker.core.model.TaskId
import app.tasker.core.model.TaskStatus
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Edit of task fields from the task card; every set field becomes the user's choice (provenance USER). */
data class TaskEdit(
    val title: FieldUpdate<String> = FieldUpdate.Keep,
    val note: FieldUpdate<String?> = FieldUpdate.Keep,
    val bucket: FieldUpdate<Bucket?> = FieldUpdate.Keep,
    val deadline: FieldUpdate<Deadline?> = FieldUpdate.Keep,
    val planDate: FieldUpdate<LocalDate?> = FieldUpdate.Keep,
    val estimate: FieldUpdate<Estimate?> = FieldUpdate.Keep,
    val projectId: FieldUpdate<ProjectId?> = FieldUpdate.Keep,
    val tags: FieldUpdate<List<String>> = FieldUpdate.Keep,
    val reminders: FieldUpdate<ReminderOffsets?> = FieldUpdate.Keep,
)

/** Start result: the task always starts; over the soft WIP limit the UI offers to pause one of [inProgress] (DAT-7). */
data class StartOutcome(val task: Task, val inProgress: List<Task>, val limit: Int) {
    val overLimit: Boolean get() = inProgress.size > limit
}

data class SplitOutcome(val project: Project, val steps: List<Task>)

data class SnapshotInput(val text: String, val kind: SnapshotInputKind = SnapshotInputKind.TEXT)

/** User commands on tasks (tech plan §8.2). Every command is one transaction, one batch and a touch. */
@Singleton
class TaskCommands @Inject constructor(
    private val runner: TxRunner,
    private val capture: CaptureService,
    private val db: TaskerDatabase,
) {
    private val taskDao = db.taskDao()

    suspend fun edit(taskId: TaskId, edit: TaskEdit): TxResult<Task> = runner.user {
        val before = task(taskId)
        (edit.title as? FieldUpdate.Set)?.let { if (it.value.isBlank()) throw InvalidCommandException("A task needs a title") }
        (edit.projectId as? FieldUpdate.Set)?.value?.let { project(it) }
        val changed = ArrayList<TaskField>()
        fun <T> value(update: FieldUpdate<T>, field: TaskField, current: T): T {
            if (update is FieldUpdate.Set && update.value != current) {
                changed += field
                return update.value
            }
            return current
        }
        val bucket = value(edit.bucket, TaskField.BUCKET, before.bucket)
        val planDate = value(edit.planDate, TaskField.PLAN_DATE, before.planDate)
        var after = before.copy(
            title = value(edit.title, TaskField.TITLE, before.title).trim(),
            note = value(edit.note, TaskField.NOTE, before.note),
            deadline = value(edit.deadline, TaskField.DEADLINE, before.deadline),
            estimate = value(edit.estimate, TaskField.ESTIMATE, before.estimate),
            tags = value(edit.tags, TaskField.TAGS, before.tags),
            projectId = value(edit.projectId, TaskField.PROJECT, before.projectId),
            bucket = bucket,
            planDate = planDate,
            reminderOffsets = edit.reminders.orElse(before.reminderOffsets),
        )
        val bucketChanged = TaskField.BUCKET in changed
        val planDateLater = TaskField.PLAN_DATE in changed && planDate?.isAfter(today) == true
        if (bucketChanged || planDateLater) after = after.copy(carryOverSince = null)
        if (bucketChanged) after = after.copy(position = nextPosition(bucket))
        if (changed.isNotEmpty()) after = FieldProvenance.mark(after, FieldSource.USER, *changed.toTypedArray())
        if ((bucketChanged && bucket != Bucket.TODAY) || (planDateLater && bucket != Bucket.TODAY)) removeFromTodaysPlan(taskId)
        val type = if (changed.isNotEmpty() && changed.all { it == TaskField.BUCKET || it == TaskField.PROJECT }) {
            EventType.MOVED
        } else {
            EventType.UPDATED
        }
        updateTask(after, type)
    }

    /** Open → In progress, or Paused → In progress. WIP limit is soft (DAT-7, interpretation 21). */
    suspend fun start(taskId: TaskId): TxResult<StartOutcome> = runner.user {
        val before = task(taskId)
        val action = if (before.status == TaskStatus.PAUSED) TaskAction.RESUME else TaskAction.START
        val status = TaskStateMachine.transition(before.status, action)
        val after = updateTask(
            before.copy(status = status, startedAt = before.startedAt ?: now),
            EventType.STATUS_CHANGED,
        )
        val inProgress = taskDao.inProgressIds().mapNotNull { taskOrNull(it) }
        StartOutcome(after, inProgress, settings.wipLimit)
    }

    /** In progress → Paused with an optional "where I stopped" snapshot (EXC-3). */
    suspend fun pause(taskId: TaskId, snapshot: SnapshotInput? = null): TxResult<Task> = runner.user {
        val before = task(taskId)
        val status = TaskStateMachine.transition(before.status, TaskAction.PAUSE)
        if (snapshot != null && snapshot.text.isNotBlank()) {
            addSnapshot(ContextSnapshot(newId(), taskId, snapshot.text.trim(), snapshot.kind, createdAt = now))
        }
        updateTask(before.copy(status = status), EventType.STATUS_CHANGED)
    }

    suspend fun resume(taskId: TaskId): TxResult<StartOutcome> = start(taskId)

    /** Swipe right / "Done" (EXC-1, EXC-7): the item of today's plan becomes DONE. */
    suspend fun complete(taskId: TaskId): TxResult<Task> = runner.user { completeIn(this, taskId) }

    suspend fun completeAll(taskIds: Collection<TaskId>): TxResult<List<Task>> = runner.user {
        taskIds.map { completeIn(this, it) }
    }

    private suspend fun completeIn(tx: Tx, taskId: TaskId): Task = with(tx) {
        val before = task(taskId)
        val status = TaskStateMachine.transition(before.status, TaskAction.COMPLETE)
        updateTask(before.copy(status = status, completedAt = now, carryOverSince = null), EventType.STATUS_CHANGED)
    }

    /** Done → Open from the done log (EXC-7). Snackbar undo restores the exact previous status instead. */
    suspend fun reopen(taskId: TaskId): TxResult<Task> = runner.user {
        val before = task(taskId)
        val status = TaskStateMachine.transition(before.status, TaskAction.REOPEN)
        updateTask(before.copy(status = status, completedAt = null, offPlan = false), EventType.STATUS_CHANGED)
    }

    /** Postpone variants (EXC-2, §10.6): the deadline never changes; the item of today's plan is removed. */
    suspend fun postpone(taskId: TaskId, option: PostponeOption): TxResult<Task> = runner.user { postponeIn(this, taskId, option) }

    /** "All to tomorrow" for the "doesn't fit" block (PLN-5, scenario 3): one batch, one undo. */
    suspend fun postponeAll(taskIds: Collection<TaskId>, option: PostponeOption): TxResult<List<Task>> = runner.user {
        taskIds.map { postponeIn(this, it, option) }
    }

    private suspend fun postponeIn(tx: Tx, taskId: TaskId, option: PostponeOption): Task = with(tx) {
        val before = task(taskId)
        if (!before.status.isActive) throw InvalidCommandException("Only unfinished tasks can be postponed")
        if (option is PostponeOption.OnDate && option.date.isBefore(today)) {
            throw InvalidCommandException("A task cannot be postponed into the past")
        }
        val inPlan = plan(today)?.item(taskId)?.outcome == PlanItemOutcome.PENDING
        val result = PostponePolicy.apply(before, option, today, inPlan)
        var after = before.copy(planDate = result.planDate, bucket = result.bucket, carryOverSince = null)
        if (result.countsAsPostpone) after = PostponeCounter.increment(after, today)
        if (after.bucket != before.bucket) after = after.copy(position = nextPosition(after.bucket))
        after = FieldProvenance.mark(after, FieldSource.USER, TaskField.PLAN_DATE, TaskField.BUCKET)
        removeFromTodaysPlan(taskId)
        updateTask(after, EventType.POSTPONED)
    }

    /**
     * Moves a task to another horizon (DAT-4): Today, Week, Someday or Inbox (`null`). Leaving today removes the task
     * from today's plan; moving to Today drops a future plan date.
     */
    suspend fun move(taskId: TaskId, bucket: Bucket?): TxResult<Task> = runner.user { moveIn(this, taskId, bucket) }

    suspend fun moveAll(taskIds: Collection<TaskId>, bucket: Bucket?): TxResult<List<Task>> = runner.user {
        taskIds.map { moveIn(this, it, bucket) }
    }

    private suspend fun moveIn(tx: Tx, taskId: TaskId, bucket: Bucket?): Task = with(tx) {
        val before = task(taskId)
        if (before.bucket == bucket) return before
        val planDate = when (bucket) {
            Bucket.TODAY -> before.planDate?.takeUnless { it.isAfter(today) }
            Bucket.WEEK -> before.planDate?.takeIf { it.isAfter(today) }
            Bucket.SOMEDAY -> null
            null -> before.planDate
        }
        var after = before.copy(bucket = bucket, planDate = planDate, carryOverSince = null, position = nextPosition(bucket))
        after = FieldProvenance.mark(after, FieldSource.USER, TaskField.BUCKET, TaskField.PLAN_DATE)
        if (bucket != Bucket.TODAY) removeFromTodaysPlan(taskId)
        updateTask(after, EventType.MOVED)
    }

    suspend fun setProject(taskId: TaskId, projectId: ProjectId?): TxResult<Task> = runner.user {
        val before = task(taskId)
        projectId?.let { project(it) }
        val after = FieldProvenance.mark(before.copy(projectId = projectId), FieldSource.USER, TaskField.PROJECT)
        updateTask(after, EventType.MOVED)
    }

    /**
     * Manual order (DAT-5). [orderedIds] is the visible list after the drag; only the moved task gets a new position,
     * unless its neighbours are adjacent and the list is rebalanced first.
     */
    suspend fun reorder(taskId: TaskId, orderedIds: List<TaskId>): TxResult<Task> = runner.user {
        val index = orderedIds.indexOf(taskId)
        if (index < 0) throw InvalidCommandException("The moved task is not in the list")
        val neighbours = tasks(listOfNotNull(orderedIds.getOrNull(index - 1), orderedIds.getOrNull(index + 1)))
            .associateBy { it.id }
        val previous = orderedIds.getOrNull(index - 1)?.let { neighbours[it]?.position }
        val next = orderedIds.getOrNull(index + 1)?.let { neighbours[it]?.position }
        val position = Positions.between(previous, next) ?: rebalance(orderedIds).getValue(taskId)
        updateTask(task(taskId).copy(position = position), EventType.REORDERED)
    }

    /** Manual archive (TTL-4, TTL-8): the reason is kept; review flag and skip streak are reset. */
    suspend fun archive(taskId: TaskId): TxResult<Task> = runner.user { archiveIn(this, taskId, ArchiveReason.USER) }

    suspend fun archiveAll(taskIds: Collection<TaskId>): TxResult<List<Task>> = runner.user {
        taskIds.map { archiveIn(this, it, ArchiveReason.USER) }
    }

    internal suspend fun archiveIn(tx: Tx, taskId: TaskId, reason: ArchiveReason, type: EventType = EventType.ARCHIVED): Task =
        with(tx) {
            val before = task(taskId)
            val status = TaskStateMachine.transition(before.status, TaskAction.ARCHIVE)
            updateTask(
                before.copy(
                    status = status,
                    archivedAt = now,
                    archiveReason = reason,
                    inReview = false,
                    reviewSince = null,
                    reviewSkipStreak = 0,
                    carryOverSince = null,
                ),
                type,
            )
        }

    /**
     * Restore from the archive in one tap (TTL-8, interpretation 15): status Open, the same bucket and project; an
     * archived project is restored too. The task returns outside review and the touch restarts its TTL.
     */
    suspend fun restore(taskId: TaskId): TxResult<Task> = runner.user { restoreIn(this, taskId) }

    internal suspend fun restoreIn(tx: Tx, taskId: TaskId): Task = with(tx) {
        val before = task(taskId)
        val status = TaskStateMachine.transition(before.status, TaskAction.RESTORE)
        before.projectId?.let { projectId ->
            val project = projectOrNull(projectId)
            if (project != null && project.status == ProjectStatus.ARCHIVED) {
                updateProject(project.copy(status = ProjectStatus.ACTIVE, archivedAt = null), EventType.RESTORED)
            }
        }
        updateTask(
            before.copy(
                status = status,
                archivedAt = null,
                archiveReason = null,
                inReview = false,
                reviewSince = null,
                reviewSkipStreak = 0,
                position = nextPosition(before.bucket),
            ),
            EventType.RESTORED,
        )
    }

    /** The only physical deletion (ARC-1); the UI asks for confirmation first. */
    suspend fun deletePermanently(taskId: TaskId): TxResult<Unit> = runner.user { deleteTask(taskId) }

    /** "Still relevant" (TTL-4): the task leaves review, its skip streak and TTL restart. */
    suspend fun confirmRelevant(taskId: TaskId): TxResult<Task> = runner.user {
        val before = task(taskId)
        updateTask(before.copy(inReview = false, reviewSince = null, reviewSkipStreak = 0), EventType.REVIEW_DECIDED)
    }

    /** Adds a context snapshot without changing the status (EXC-4). */
    suspend fun addSnapshot(taskId: TaskId, input: SnapshotInput): TxResult<Task> = runner.user {
        if (input.text.isBlank()) throw InvalidCommandException("A snapshot needs text")
        addSnapshot(ContextSnapshot(newId(), taskId, input.text.trim(), input.kind, createdAt = now))
        updateTask(task(taskId), EventType.SNAPSHOT_ADDED)
    }

    /** DAT-3 "keep": remembers the level, so the next question comes after another threshold of postpones. */
    suspend fun keepAfterPostponeQuestion(taskId: TaskId): TxResult<Task> = runner.user {
        val before = task(taskId)
        updateTask(before.copy(postponePromptedAt = before.postponeCount), EventType.UPDATED)
    }

    suspend fun setReminders(taskId: TaskId, offsets: ReminderOffsets?): TxResult<Task> = runner.user {
        updateTask(task(taskId).copy(reminderOffsets = offsets), EventType.UPDATED)
    }

    /**
     * Split (DAT-3, PLN-8, EXE-3, §8.5): the task becomes a project with its title; every line becomes a task of the
     * project through the regular parser. The first step inherits the bucket, the plan date and the place in today's
     * plan, the deadline moves to the last step, the note and sources go to the first step. The original task is
     * archived with reason SPLIT, so nothing is lost.
     */
    suspend fun split(taskId: TaskId, lines: List<String>): TxResult<SplitOutcome> {
        val steps = SplitPlanner.normalizeSteps(lines)
        if (steps.size !in SplitPlanner.MIN_STEPS..SplitPlanner.MAX_STEPS) {
            throw InvalidCommandException("A split needs ${SplitPlanner.MIN_STEPS}..${SplitPlanner.MAX_STEPS} steps")
        }
        val parsed = steps.map { capture.parse(it) }
        return runner.user {
            val original = task(taskId)
            if (!original.status.isActive) throw InvalidCommandException("Only unfinished tasks can be split")
            val seeds = SplitPlanner.seeds(original, steps)
            val project = insertProject(
                Project(
                    id = newId(),
                    name = original.title,
                    position = nextProjectPosition(),
                    lastActivityAt = now,
                    createdAt = now,
                ),
            )
            val todaysItem = plan(today)?.item(taskId)?.takeIf { it.outcome == PlanItemOutcome.PENDING }
            val sources = db.contentDao().sources(taskId)
            val created = seeds.mapIndexed { index, seed ->
                val parse = parsed[index]
                val fields = HashMap<TaskField, FieldSource>()
                fields[TaskField.TITLE] = FieldSource.USER
                fields[TaskField.PROJECT] = FieldSource.USER
                val bucket = parse.bucket ?: seed.bucket
                val step = Task(
                    id = newId(),
                    title = parse.title,
                    rawInput = steps[index],
                    note = seed.note,
                    bucket = bucket,
                    position = nextPosition(bucket),
                    deadline = parse.deadline?.let { Deadline(it.date, it.time, if (it.time != null) zone else null) }
                        ?: seed.deadline,
                    planDate = parse.planDate ?: seed.planDate,
                    estimate = parse.estimate,
                    projectId = project.id,
                    tags = (original.tags + parse.tags).distinct(),
                    lastTouchedAt = now,
                    captureChannel = original.captureChannel,
                    fieldSources = fields,
                    enrichState = if (settings.ai.isActive && parse.estimate == null) EnrichState.PENDING else EnrichState.NONE,
                    createdAt = now,
                )
                val stored = insertTask(step)
                if (seed.copySources) {
                    sources.forEach { source -> addSource(source.toModelCopy(newId(), stored.id)) }
                }
                stored
            }
            archiveIn(this, taskId, ArchiveReason.SPLIT, EventType.SPLIT)
            if (todaysItem != null) {
                val first = created.first()
                val plan = checkNotNull(plan(today))
                savePlan(
                    plan.withItem(
                        DayPlanItem(
                            date = today,
                            taskId = first.id,
                            position = todaysItem.position,
                            minutes = settings.estimateMinutes.of(first.estimate),
                            origin = todaysItem.origin,
                            candidateGroup = todaysItem.candidateGroup,
                        ),
                    ),
                )
            }
            SplitOutcome(project, created)
        }
    }

    private fun app.tasker.core.database.entity.SourceEntity.toModelCopy(id: String, taskId: TaskId) =
        app.tasker.core.model.Source(
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
        )
}
