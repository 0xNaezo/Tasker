package app.tasker.core.data.plan

import app.tasker.core.domain.capacity.Capacity
import app.tasker.core.domain.plan.AutoPlanner
import app.tasker.core.domain.plan.Candidate
import app.tasker.core.domain.plan.CandidateBuilder
import app.tasker.core.domain.plan.CandidateReason
import app.tasker.core.domain.plan.PlanProposal
import app.tasker.core.domain.postpone.PostponeCounter
import app.tasker.core.model.AppSettings
import app.tasker.core.model.CandidateGroup
import app.tasker.core.model.ContextSnapshot
import app.tasker.core.model.DayPlan
import app.tasker.core.model.DayPlanItem
import app.tasker.core.model.Estimate
import app.tasker.core.model.PlanItemOutcome
import app.tasker.core.model.PlanState
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A plan item with its task and the reason it is a candidate today (principle 7); [reason] is null for manual picks. */
data class PlanEntry(
    val item: DayPlanItem,
    val task: Task,
    val group: CandidateGroup?,
    val reason: CandidateReason?,
) {
    val isDone: Boolean get() = item.outcome == PlanItemOutcome.DONE || task.status == TaskStatus.DONE

    /** PLN-8: large tasks in the plan get a "split" hint. */
    val suggestSplit: Boolean get() = task.estimate == Estimate.L && !isDone
}

/** A task in progress or paused with its latest context snapshot (EXC-4). */
data class WorkEntry(val task: Task, val latestSnapshot: ContextSnapshot?)

/**
 * Everything the Today and Plan screens show for one day (tech plan §10, §14.2), computed from the stored state and
 * "now". Derived values (capacity, overload, candidates) are never stored.
 */
data class DayView(
    val day: LocalDate,
    val now: Instant,
    val settings: AppSettings,
    val capacity: Capacity,
    val plan: DayPlan?,
    /** Items of the plan that were not removed, in plan order; done items stay visible. */
    val entries: List<PlanEntry>,
    /** All candidates by group (PLN-4). */
    val candidates: List<Candidate>,
    /** Live auto-selection for a draft or when there is no plan yet. Null for an accepted or hand-edited plan. */
    val proposal: PlanProposal?,
    val work: List<WorkEntry>,
    /** Urgent candidates (groups 1–2) that are not in the accepted plan: shown as "add" hints (§10.5, step 5). */
    val suggestions: List<Candidate>,
    /** DAT-3 questions: tasks that reached the postpone threshold (one batch of questions, principle 2). */
    val questions: List<Task>,
    val reviewCount: Int,
    val inboxCount: Int,
) {
    val isAccepted: Boolean get() = plan?.state == PlanState.ACCEPTED

    /** Minutes of unfinished planned items; overload is counted against the remaining capacity (§10.1). */
    val plannedMinutes: Int get() = entries.filter { !it.isDone }.sumOf { it.item.minutes }

    val overloadMinutes: Int get() = capacity.overloadFor(plannedMinutes)

    val inProgressCount: Int get() = work.count { it.task.status == TaskStatus.IN_PROGRESS }

    val overWipLimit: Boolean get() = inProgressCount > settings.wipLimit

    val inboxOverflow: Boolean get() = inboxCount > settings.inboxTriageThreshold

    /** PLN-9: without an accepted plan Today shows candidates of groups 1–4 without auto-selection. */
    val unplannedCandidates: List<Candidate>
        get() = candidates.filter { it.group != CandidateGroup.WEEK }

    val doesNotFit: List<Candidate> get() = proposal?.doesNotFit.orEmpty()
}

object DayViews {
    @Suppress("LongParameterList")
    fun build(
        day: LocalDate,
        now: Instant,
        zone: ZoneId,
        settings: AppSettings,
        capacity: Capacity,
        plan: DayPlan?,
        activeTasks: List<Task>,
        planTasks: Map<String, Task>,
        snapshots: Map<String, ContextSnapshot>,
        reviewCount: Int,
        inboxCount: Int,
    ): DayView {
        val candidates = CandidateBuilder.build(activeTasks, day, now, zone, settings)
        val byTask = candidates.associateBy { it.task.id }
        val entries = plan?.activeItems.orEmpty().mapNotNull { item ->
            val task = planTasks[item.taskId] ?: return@mapNotNull null
            if (task.status == TaskStatus.ARCHIVED) return@mapNotNull null
            val candidate = byTask[item.taskId]
            PlanEntry(item, task, candidate?.group ?: item.candidateGroup, candidate?.reason)
        }
        val planned = entries.mapTo(HashSet()) { it.task.id }
        val removed = plan?.items.orEmpty().filter { it.outcome == PlanItemOutcome.REMOVED }.mapTo(HashSet()) { it.taskId }
        val autoDraft =
            plan == null || (plan.state == PlanState.DRAFT && plan.items.all { it.origin == app.tasker.core.model.PlanItemOrigin.AUTO })
        val proposal = if (autoDraft) {
            AutoPlanner.propose(candidates.filter { it.task.id !in removed }, capacity.capacityMin)
        } else {
            null
        }
        val accepted = plan?.state == PlanState.ACCEPTED
        val suggestions = if (accepted) {
            candidates.filter { it.group.autoSelectsInReview && it.task.id !in planned && it.task.id !in removed }
        } else {
            emptyList()
        }
        val work = activeTasks
            .filter { it.status == TaskStatus.IN_PROGRESS || it.status == TaskStatus.PAUSED }
            .sortedWith(compareBy<Task> { if (it.status == TaskStatus.IN_PROGRESS) 0 else 1 }.thenByDescending { it.lastTouchedAt })
            .map { WorkEntry(it, snapshots[it.id]) }
        val questionPool = (activeTasks.filter { it.id in planned || it.id in byTask.keys })
        val questions = questionPool.filter { PostponeCounter.needsQuestion(it, settings.postponeThreshold) }
            .sortedByDescending { it.postponeCount }
        return DayView(
            day = day,
            now = now,
            settings = settings,
            capacity = capacity,
            plan = plan,
            entries = entries,
            candidates = candidates,
            proposal = proposal,
            work = work,
            suggestions = suggestions,
            questions = questions,
            reviewCount = reviewCount,
            inboxCount = inboxCount,
        )
    }
}
