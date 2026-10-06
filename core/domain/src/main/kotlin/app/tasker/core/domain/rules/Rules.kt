package app.tasker.core.domain.rules

import app.tasker.core.domain.plan.TaskTiming
import app.tasker.core.domain.postpone.PostponeCounter
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AppSettings
import app.tasker.core.model.ArchiveReason
import app.tasker.core.model.Bucket
import app.tasker.core.model.DayPlan
import app.tasker.core.model.EventType
import app.tasker.core.model.PlanItemOutcome
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectStatus
import app.tasker.core.model.Reason
import app.tasker.core.model.ReasonCode
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import java.time.Instant
import java.time.LocalDate

/** A change a rule wants to make to one task; the data layer persists it with an event (actor RULE). */
data class RuleOutcome(
    val before: Task,
    val after: Task,
    val eventType: EventType,
    val reason: Reason,
)

data class ProjectRuleOutcome(
    val before: Project,
    val after: Project,
    val eventType: EventType,
    val reason: Reason,
)

/** End of day decisions for an accepted plan: new item outcomes plus task changes (R1, PLN-10). */
data class EndOfDayResult(
    val itemOutcomes: Map<String, PlanItemOutcome>,
    val taskOutcomes: List<RuleOutcome>,
)

/**
 * Automation rules (tech plan §11.2). Every function is pure and idempotent: applied twice to the same state it
 * changes nothing the second time. Rules never touch `lastTouchedAt` (TTL-1 invariant).
 */
object Rules {
    /**
     * R2 — plan date passed (DAT-2). An unfinished task whose plan date is before today gets +1 postpone once per
     * plan date (marker `planDateRolled`), attributed to the plan date itself. The task is not overdue and stays a
     * candidate for the next days.
     */
    fun planDatePassed(task: Task, today: LocalDate): RuleOutcome? {
        val planDate = task.planDate ?: return null
        if (!task.status.isActive || !planDate.isBefore(today) || task.planDateRolled == planDate) return null
        val after = PostponeCounter.increment(task.copy(planDateRolled = planDate), planDate)
        return RuleOutcome(
            before = task,
            after = after,
            eventType = EventType.ROLLED_OVER,
            reason = Reason(ReasonCode.PLAN_DATE_PASSED, mapOf("date" to planDate.toString())),
        )
    }

    /**
     * R1 — end of logical day [day] (PLN-10, §10.6). For an accepted plan, unfinished items become CARRIED_OVER,
     * their tasks get `carryOverSince` and +1 postpone. On a working day with user activity, unfinished tasks of the
     * Today bucket get +1 postpone too. Weekends without a plan and days without opening the app add nothing.
     */
    fun endOfDay(
        day: LocalDate,
        plan: DayPlan?,
        tasksById: Map<String, Task>,
        todayBucketTasks: Collection<Task>,
        userWasActive: Boolean,
        isWorkDay: Boolean,
    ): EndOfDayResult {
        val itemOutcomes = LinkedHashMap<String, PlanItemOutcome>()
        val outcomes = LinkedHashMap<String, RuleOutcome>()
        if (plan != null && plan.isAccepted && plan.date == day) {
            for (item in plan.activeItems) {
                if (item.outcome != PlanItemOutcome.PENDING) continue
                val task = tasksById[item.taskId]
                when {
                    task == null || task.status == TaskStatus.ARCHIVED -> itemOutcomes[item.taskId] = PlanItemOutcome.REMOVED
                    task.status == TaskStatus.DONE -> itemOutcomes[item.taskId] = PlanItemOutcome.DONE
                    else -> {
                        itemOutcomes[item.taskId] = PlanItemOutcome.CARRIED_OVER
                        val after = PostponeCounter.increment(task.copy(carryOverSince = task.carryOverSince ?: day), day)
                        if (after != task) {
                            outcomes[task.id] = RuleOutcome(
                                before = task,
                                after = after,
                                eventType = EventType.ROLLED_OVER,
                                reason = Reason(ReasonCode.PLAN_NOT_DONE, mapOf("date" to day.toString())),
                            )
                        }
                    }
                }
            }
        }
        if (userWasActive && isWorkDay) {
            for (task in todayBucketTasks) {
                if (task.id in outcomes || task.id in itemOutcomes) continue
                if (!task.status.isActive || task.bucket != Bucket.TODAY) continue
                if (!PostponeCounter.canCount(task, day)) continue
                outcomes[task.id] = RuleOutcome(
                    before = task,
                    after = PostponeCounter.increment(task, day),
                    eventType = EventType.ROLLED_OVER,
                    reason = Reason(ReasonCode.TODAY_NOT_DONE, mapOf("date" to day.toString())),
                )
            }
        }
        return EndOfDayResult(itemOutcomes, outcomes.values.toList())
    }

    /** TTL of a task: Inbox, Today/Week, Someday (TTL-2). Project tasks without a bucket use the Week TTL. */
    fun ttlDays(task: Task, settings: AppSettings): Int =
        if (task.bucket == null && task.projectId != null) settings.ttl.todayWeek else settings.ttl.forBucket(task.bucket)

    /** R3 — TTL expired (TTL-1…3): an open task not touched for its TTL enters the review queue, never the archive. */
    fun ttlExpired(task: Task, today: LocalDate, now: Instant, settings: AppSettings, clock: DayClock): RuleOutcome? {
        if (task.status != TaskStatus.OPEN || task.inReview) return null
        val ttl = ttlDays(task, settings)
        val idle = clock.daysSince(task.lastTouchedAt, today)
        if (idle < ttl) return null
        return RuleOutcome(
            before = task,
            after = task.copy(inReview = true, reviewSince = now, reviewSkipStreak = 0),
            eventType = EventType.REVIEW_ENTERED,
            reason = Reason(
                ReasonCode.TTL_EXPIRED,
                mapOf("days" to idle.toString(), "bucket" to (task.bucket?.name ?: INBOX)),
            ),
        )
    }

    /**
     * R4, part 1 — at the end of [day]: a task whose review card was shown that day without a decision gets +1 to
     * the skip streak (at most once per day, interpretation 10). Inbox triage is not counted.
     */
    fun reviewSkipped(task: Task, day: LocalDate, shownOnDay: Boolean, decidedOnDay: Boolean): RuleOutcome? {
        if (!shownOnDay || decidedOnDay || !task.inReview || task.status != TaskStatus.OPEN) return null
        val streak = task.reviewSkipStreak + 1
        return RuleOutcome(
            before = task,
            after = task.copy(reviewSkipStreak = streak),
            eventType = EventType.REVIEW_SKIPPED,
            reason = Reason(ReasonCode.REVIEW_SKIPPED, mapOf("streak" to streak.toString(), "date" to day.toString())),
        )
    }

    /**
     * R4, part 2 — automatic archive (TTL-6, TTL-7): an open task in review skipped twice in a row is archived,
     * unless its deadline is still ahead. The review flag and the streak are reset.
     */
    fun autoArchive(task: Task, now: Instant, today: LocalDate, clock: DayClock): RuleOutcome? {
        if (task.status != TaskStatus.OPEN || !task.inReview || task.reviewSkipStreak < SKIPS_TO_ARCHIVE) return null
        if (TaskTiming.hasFutureDeadline(task, now, today, clock.zone())) return null
        return RuleOutcome(
            before = task,
            after = task.copy(
                status = TaskStatus.ARCHIVED,
                archivedAt = now,
                archiveReason = ArchiveReason.TTL_SKIPS,
                inReview = false,
                reviewSince = null,
                reviewSkipStreak = 0,
                carryOverSince = null,
            ),
            eventType = EventType.ARCHIVED,
            reason = Reason(ReasonCode.TTL_SKIPS, mapOf("skips" to task.reviewSkipStreak.toString())),
        )
    }

    /** R5 — active project without activity for the project TTL goes to review; projects are never auto-archived. */
    fun projectInactive(project: Project, today: LocalDate, now: Instant, settings: AppSettings, clock: DayClock): ProjectRuleOutcome? {
        if (project.status != ProjectStatus.ACTIVE || project.inReview) return null
        val idle = clock.daysSince(project.lastActivityAt, today)
        if (idle < settings.ttl.project) return null
        return ProjectRuleOutcome(
            before = project,
            after = project.copy(inReview = true, reviewSince = now),
            eventType = EventType.REVIEW_ENTERED,
            reason = Reason(ReasonCode.PROJECT_INACTIVE, mapOf("days" to idle.toString())),
        )
    }

    /** R6 — "no next step" is derived: an active project without open, in-progress or paused tasks (EXE-5). */
    fun hasNoNextStep(project: Project, projectTasks: Collection<Task>): Boolean =
        project.status == ProjectStatus.ACTIVE && projectTasks.none { it.projectId == project.id && it.status.isActive }

    /** R8 — Inbox overflow suggests quick triage (TTL-9). */
    fun inboxOverflow(inboxCount: Int, settings: AppSettings): Boolean = inboxCount > settings.inboxTriageThreshold

    const val SKIPS_TO_ARCHIVE = 2
    const val INBOX = "INBOX"
}
