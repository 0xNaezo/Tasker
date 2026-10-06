package app.tasker.core.data.plan

import app.tasker.core.data.command.InvalidCommandException
import app.tasker.core.data.command.Tx
import app.tasker.core.data.command.TxResult
import app.tasker.core.data.command.TxRunner
import app.tasker.core.data.command.isFrozenDraft
import app.tasker.core.data.command.item
import app.tasker.core.data.command.nextPosition
import app.tasker.core.data.command.withItem
import app.tasker.core.data.mapper.toModel
import app.tasker.core.data.repository.BusyTimeRepository
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.domain.capacity.Capacity
import app.tasker.core.domain.plan.AutoPlanner
import app.tasker.core.domain.plan.CandidateBuilder
import app.tasker.core.domain.plan.PlanChange
import app.tasker.core.domain.plan.PlanProposal
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.DayPlan
import app.tasker.core.model.DayPlanItem
import app.tasker.core.model.EventType
import app.tasker.core.model.PlanItemOrigin
import app.tasker.core.model.PlanItemOutcome
import app.tasker.core.model.PlanState
import app.tasker.core.model.TaskId
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Result of preparing the plan of a day: the stored plan and, for a refreshed draft, what changed (§10.5, step 1). */
data class PreparedPlan(
    val plan: DayPlan,
    val capacity: Capacity,
    val proposal: PlanProposal?,
    val change: PlanChange?,
)

/**
 * Day plan lifecycle (PLN-4…PLN-10, tech plan §10.5): draft before the morning notification, refresh on opening,
 * one-tap acceptance with a capacity snapshot, manual edits, late additions.
 */
@Singleton
class PlanService @Inject constructor(
    private val runner: TxRunner,
    private val clock: DayClock,
    private val settings: SettingsRepository,
    private val busy: BusyTimeRepository,
    private val db: TaskerDatabase,
) {
    /**
     * Builds the draft of [day], or refreshes it if it was not edited by hand, so the notification and the screen show
     * the same numbers. An accepted or hand-edited plan is returned as it is. Not a user action: no touches, no events.
     */
    suspend fun prepare(day: LocalDate = clock.today()): PreparedPlan {
        val current = settings.current()
        val now = clock.now()
        val capacity = busy.capacity(day, current, now)
        val tasks = db.taskDao().activeTasks().map { it.toModel() }
        val candidates = CandidateBuilder.build(tasks, day, now, clock.zone(), current)
        val result = runner.run(app.tasker.core.model.Actor.RULE) {
            val existing = plan(day)
            if (existing != null && (existing.isAccepted || existing.isFrozenDraft)) {
                return@run PreparedPlan(existing, capacity, null, null)
            }
            val removed = existing?.items.orEmpty().filter { it.outcome == PlanItemOutcome.REMOVED }.map { it.taskId }.toSet()
            val proposal = AutoPlanner.propose(candidates.filter { it.task.id !in removed }, capacity.capacityMin)
            val change = existing?.let { draft ->
                PlanChange.between(draft.capacityMin, draft.activeItems.map { it.taskId }, proposal)
            }
            val items = proposal.selected.mapIndexed { index, candidate ->
                DayPlanItem(
                    date = day,
                    taskId = candidate.task.id,
                    position = index + 1,
                    minutes = candidate.minutes,
                    origin = PlanItemOrigin.AUTO,
                    candidateGroup = candidate.group,
                )
            }
            val draft = DayPlan(
                date = day,
                state = PlanState.DRAFT,
                createdAt = existing?.createdAt ?: now,
                workingMin = capacity.workingMin,
                busyMin = capacity.busyMin,
                capacityMin = capacity.capacityMin,
                plannedMin = proposal.plannedMin,
                items = items,
            )
            savePlan(draft)
            PreparedPlan(draft, capacity, proposal, change?.takeIf { it.hasChanges })
        }
        return result.value
    }

    /**
     * "Accept" in one tap (PLN-6): the plan is fixed with the capacity snapshot for metrics. Not a touch
     * (interpretation 23): otherwise tasks proposed every day would never get stale.
     */
    suspend fun accept(day: LocalDate = clock.today()): TxResult<DayPlan> {
        val prepared = prepare(day)
        return runner.user {
            val plan = plan(day) ?: prepared.plan
            if (plan.isAccepted) return@user plan
            val planned = plan.activeItems.filter { it.outcome == PlanItemOutcome.PENDING }.sumOf { it.minutes }
            val accepted = plan.copy(
                state = PlanState.ACCEPTED,
                acceptedAt = now,
                workingMin = prepared.capacity.workingMin,
                busyMin = prepared.capacity.busyMin,
                capacityMin = prepared.capacity.capacityMin,
                plannedMin = planned,
            )
            savePlan(accepted)
            accepted
        }
    }

    /** Adds a task to the plan by hand (PLN-7: never blocked by overload). A touch of the task. */
    suspend fun add(taskId: TaskId, day: LocalDate = clock.today()): TxResult<DayPlan> = runner.user { addIn(this, taskId, day) }

    private suspend fun addIn(tx: Tx, taskId: TaskId, day: LocalDate): DayPlan = with(tx) {
        val task = task(taskId)
        if (!task.status.isActive) throw InvalidCommandException("Only unfinished tasks can be planned")
        val plan = editablePlan(day)
        val existing = plan.item(taskId)
        val group = CandidateBuilder.classify(task, day, now, zone, settings)?.group
        val item = when {
            existing == null -> DayPlanItem(
                date = day,
                taskId = taskId,
                position = plan.nextPosition(),
                minutes = settings.estimateMinutes.of(task.estimate),
                origin = PlanItemOrigin.MANUAL,
                candidateGroup = group,
            )
            existing.outcome == PlanItemOutcome.REMOVED -> existing.copy(outcome = PlanItemOutcome.PENDING, position = plan.nextPosition())
            else -> return plan
        }
        val updated = plan.withItem(item)
        savePlan(updated)
        updateTask(task, EventType.PLAN_CHANGED)
        updated
    }

    /** Removes a task from the plan; the item stays with outcome REMOVED for the metrics. */
    suspend fun remove(taskId: TaskId, day: LocalDate = clock.today()): TxResult<DayPlan> = runner.user {
        val plan = editablePlan(day)
        val item = plan.item(taskId) ?: return@user plan
        val updated = plan.withItem(item.copy(outcome = PlanItemOutcome.REMOVED))
        savePlan(updated)
        updated
    }

    /** "Replace" for a task that does not fit (PLN-5): add it and take the chosen task out of the plan. */
    suspend fun replace(addTaskId: TaskId, evictTaskId: TaskId, day: LocalDate = clock.today()): TxResult<DayPlan> = runner.user {
        val plan = editablePlan(day)
        plan.item(evictTaskId)?.let { savePlan(plan.withItem(it.copy(outcome = PlanItemOutcome.REMOVED))) }
        addIn(this, addTaskId, day)
    }

    /** Manual order of the plan (a touch of the moved task); [orderedIds] is the visible order after the drag. */
    suspend fun reorder(taskId: TaskId, orderedIds: List<TaskId>, day: LocalDate = clock.today()): TxResult<DayPlan> = runner.user {
        val plan = editablePlan(day)
        val order = orderedIds.withIndex().associate { (index, id) -> id to index + 1 }
        val rest = plan.items.filter { it.taskId !in order }.sortedBy { it.position }
        val items = plan.items.map { item ->
            val position = order[item.taskId] ?: (orderedIds.size + rest.indexOf(item) + 1)
            item.copy(position = position)
        }
        val updated = plan.copy(items = items)
        savePlan(updated)
        updateTask(task(taskId), EventType.PLAN_CHANGED)
        updated
    }

    /** The plan of [day] ready for a manual edit: created if missing; a draft edited by hand stops auto refresh. */
    private suspend fun Tx.editablePlan(day: LocalDate): DayPlan {
        val existing = plan(day) ?: DayPlan(date = day, state = PlanState.DRAFT, createdAt = now)
        return if (existing.state == PlanState.DRAFT) {
            existing.copy(
                items = existing.items.map {
                    if (it.origin ==
                        PlanItemOrigin.AUTO
                    ) {
                        it.copy(origin = PlanItemOrigin.MANUAL)
                    } else {
                        it
                    }
                },
            )
        } else {
            existing
        }
    }
}
