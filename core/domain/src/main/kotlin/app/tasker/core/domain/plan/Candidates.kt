package app.tasker.core.domain.plan

import app.tasker.core.model.AppSettings
import app.tasker.core.model.Bucket
import app.tasker.core.model.CandidateGroup
import app.tasker.core.model.Deadline
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Why a task is a candidate for today; shown next to every candidate (principle 7). */
sealed interface CandidateReason {
    data class Overdue(val deadline: Deadline) : CandidateReason

    data class DeadlineSoon(val deadline: Deadline) : CandidateReason

    data object InProgress : CandidateReason

    data object Paused : CandidateReason

    /** Left over from an unfinished plan of [since]. */
    data class CarriedOver(val since: LocalDate) : CandidateReason

    /** Plan date passed without completion. */
    data class PlanDatePassed(val planDate: LocalDate) : CandidateReason

    /** Plan date is today or the task is in the Today bucket. */
    data object ForToday : CandidateReason

    data object FromWeek : CandidateReason
}

data class Candidate(
    val task: Task,
    val group: CandidateGroup,
    val reason: CandidateReason,
    val minutes: Int,
) {
    val inReview: Boolean get() = task.inReview

    /** Tasks in review are auto-selected only from the deadline groups (interpretation 22). */
    val autoSelectable: Boolean get() = !inReview || group.autoSelectsInReview
}

/**
 * Candidates for the day plan (PLN-4, tech plan §10.3). Each task falls into the first matching group:
 * 1 overdue deadline → 2 deadline within N days → 3 in progress, then paused → 4 plan date ≤ today,
 * Today bucket without a future plan date, carried over → 5 Week bucket without a future plan date.
 * Done and archived tasks, Someday and Inbox tasks without dates are not candidates.
 */
object CandidateBuilder {
    fun build(
        tasks: Collection<Task>,
        today: LocalDate,
        now: Instant,
        zone: ZoneId,
        settings: AppSettings,
    ): List<Candidate> {
        val grouped = tasks.asSequence()
            .filter { it.status.isActive }
            .mapNotNull { classify(it, today, now, zone, settings) }
            .groupBy { it.group }
        return CandidateGroup.entries.sortedBy { it.order }.flatMap { group ->
            val items = grouped[group].orEmpty()
            when (group) {
                CandidateGroup.OVERDUE, CandidateGroup.DEADLINE_SOON ->
                    items.sortedWith(compareBy({ TaskTiming.sortKey(it.task.deadline!!) }, { it.task.position }))
                CandidateGroup.IN_PROGRESS ->
                    items.sortedWith(
                        compareBy<Candidate> { if (it.task.status == TaskStatus.IN_PROGRESS) 0 else 1 }
                            .thenByDescending { it.task.lastTouchedAt },
                    )
                CandidateGroup.PLANNED ->
                    items.sortedWith(compareBy({ plannedSortDate(it.task, today) }, { it.task.position }))
                CandidateGroup.WEEK -> items.sortedBy { it.task.position }
            }
        }
    }

    fun classify(task: Task, today: LocalDate, now: Instant, zone: ZoneId, settings: AppSettings): Candidate? {
        if (!task.status.isActive) return null
        val minutes = settings.estimateMinutes.of(task.estimate)
        val deadline = task.deadline
        if (deadline != null && TaskTiming.isOverdue(deadline, now, today, zone)) {
            return Candidate(task, CandidateGroup.OVERDUE, CandidateReason.Overdue(deadline), minutes)
        }
        if (deadline != null && TaskTiming.isDeadlineSoon(deadline, today, settings.deadlineHorizonDays)) {
            return Candidate(task, CandidateGroup.DEADLINE_SOON, CandidateReason.DeadlineSoon(deadline), minutes)
        }
        when (task.status) {
            TaskStatus.IN_PROGRESS -> return Candidate(task, CandidateGroup.IN_PROGRESS, CandidateReason.InProgress, minutes)
            TaskStatus.PAUSED -> return Candidate(task, CandidateGroup.IN_PROGRESS, CandidateReason.Paused, minutes)
            else -> Unit
        }
        val planDate = task.planDate
        val futurePlanDate = planDate != null && planDate.isAfter(today)
        val carriedOver = task.carryOverSince
        return when {
            carriedOver != null && !futurePlanDate ->
                Candidate(task, CandidateGroup.PLANNED, CandidateReason.CarriedOver(carriedOver), minutes)
            planDate != null && planDate.isBefore(today) ->
                Candidate(task, CandidateGroup.PLANNED, CandidateReason.PlanDatePassed(planDate), minutes)
            planDate == today -> Candidate(task, CandidateGroup.PLANNED, CandidateReason.ForToday, minutes)
            task.bucket == Bucket.TODAY && !futurePlanDate ->
                Candidate(task, CandidateGroup.PLANNED, CandidateReason.ForToday, minutes)
            task.bucket == Bucket.WEEK && !futurePlanDate ->
                Candidate(task, CandidateGroup.WEEK, CandidateReason.FromWeek, minutes)
            else -> null
        }
    }

    private fun plannedSortDate(task: Task, today: LocalDate): LocalDate =
        listOfNotNull(task.planDate, task.carryOverSince).minOrNull() ?: today
}
