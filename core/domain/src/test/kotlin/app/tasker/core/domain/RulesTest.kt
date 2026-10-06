package app.tasker.core.domain

import app.tasker.core.domain.rules.Rules
import app.tasker.core.model.AppSettings
import app.tasker.core.model.ArchiveReason
import app.tasker.core.model.Bucket
import app.tasker.core.model.DayPlan
import app.tasker.core.model.DayPlanItem
import app.tasker.core.model.Deadline
import app.tasker.core.model.PlanItemOrigin
import app.tasker.core.model.PlanItemOutcome
import app.tasker.core.model.PlanState
import app.tasker.core.model.ReasonCode
import app.tasker.core.model.TaskStatus
import app.tasker.core.testing.TestClock
import app.tasker.core.testing.aProject
import app.tasker.core.testing.aTask
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Test

class RulesTest {
    private val time = TestClock()
    private val clock = time.clock
    private val today = date("2026-10-06")
    private val settings = AppSettings()

    @Test
    fun `R2 counts a passed plan date once and is idempotent`() {
        val task = aTask(planDate = date("2026-10-05"))
        val outcome = Rules.planDatePassed(task, today)!!
        assertThat(outcome.after.postponeCount).isEqualTo(1)
        assertThat(outcome.after.planDateRolled).isEqualTo(date("2026-10-05"))
        assertThat(outcome.after.lastPostponeDay).isEqualTo(date("2026-10-05"))
        assertThat(outcome.reason.code).isEqualTo(ReasonCode.PLAN_DATE_PASSED)
        assertThat(Rules.planDatePassed(outcome.after, today)).isNull()
        assertThat(Rules.planDatePassed(task.copy(planDate = today), today)).isNull()
    }

    @Test
    fun `R2 does not double count a day already counted by R1`() {
        val task = aTask(planDate = date("2026-10-05")).copy(postponeCount = 1, lastPostponeDay = date("2026-10-05"))
        val outcome = Rules.planDatePassed(task, today)!!
        assertThat(outcome.after.postponeCount).isEqualTo(1)
        assertThat(outcome.after.planDateRolled).isEqualTo(date("2026-10-05"))
    }

    @Test
    fun `R1 carries over unfinished items of an accepted plan`() {
        val day = date("2026-10-05")
        val done = aTask(id = "done", status = TaskStatus.DONE)
        val open = aTask(id = "open", bucket = Bucket.WEEK)
        val plan = DayPlan(
            date = day,
            state = PlanState.ACCEPTED,
            createdAt = clock.now(),
            items = listOf(
                DayPlanItem(day, "done", 1, 60, PlanItemOrigin.AUTO),
                DayPlanItem(day, "open", 2, 60, PlanItemOrigin.AUTO),
            ),
        )
        val result = Rules.endOfDay(day, plan, mapOf("done" to done, "open" to open), emptyList(), userWasActive = false, isWorkDay = true)
        assertThat(result.itemOutcomes).containsExactly("done", PlanItemOutcome.DONE, "open", PlanItemOutcome.CARRIED_OVER)
        val changed = result.taskOutcomes.single().after
        assertThat(changed.carryOverSince).isEqualTo(day)
        assertThat(changed.postponeCount).isEqualTo(1)
        assertThat(result.taskOutcomes.single().reason.code).isEqualTo(ReasonCode.PLAN_NOT_DONE)
    }

    @Test
    fun `R1 counts the Today bucket only on an active working day`() {
        val day = date("2026-10-05")
        val todayTask = aTask(bucket = Bucket.TODAY)
        val inactive = Rules.endOfDay(day, null, emptyMap(), listOf(todayTask), userWasActive = false, isWorkDay = true)
        assertThat(inactive.taskOutcomes).isEmpty()
        val weekend = Rules.endOfDay(day, null, emptyMap(), listOf(todayTask), userWasActive = true, isWorkDay = false)
        assertThat(weekend.taskOutcomes).isEmpty()
        val active = Rules.endOfDay(day, null, emptyMap(), listOf(todayTask), userWasActive = true, isWorkDay = true)
        assertThat(active.taskOutcomes.single().reason.code).isEqualTo(ReasonCode.TODAY_NOT_DONE)
    }

    @Test
    fun `R3 sends an untouched task to review, never to the archive`() {
        val touched = clock.now().minus(Duration.ofDays(7))
        val inbox = aTask(lastTouchedAt = touched)
        val outcome = Rules.ttlExpired(inbox, today, clock.now(), settings, clock)!!
        assertThat(outcome.after.inReview).isTrue()
        assertThat(outcome.after.status).isEqualTo(TaskStatus.OPEN)
        assertThat(outcome.reason.params).containsEntry("bucket", "INBOX")
        assertThat(outcome.after.lastTouchedAt).isEqualTo(inbox.lastTouchedAt)

        assertThat(
            Rules.ttlExpired(aTask(lastTouchedAt = clock.now().minus(Duration.ofDays(6))), today, clock.now(), settings, clock),
        ).isNull()
        assertThat(Rules.ttlExpired(inbox.copy(status = TaskStatus.IN_PROGRESS), today, clock.now(), settings, clock)).isNull()
        val someday = aTask(bucket = Bucket.SOMEDAY, lastTouchedAt = clock.now().minus(Duration.ofDays(59)))
        assertThat(Rules.ttlExpired(someday, today, clock.now(), settings, clock)).isNull()
    }

    @Test
    fun `R4 counts a shown but undecided card and archives after two skips`() {
        val task = aTask(inReview = true)
        assertThat(Rules.reviewSkipped(task, today, shownOnDay = true, decidedOnDay = true)).isNull()
        assertThat(Rules.reviewSkipped(task, today, shownOnDay = false, decidedOnDay = false)).isNull()
        val once = Rules.reviewSkipped(task, today, shownOnDay = true, decidedOnDay = false)!!.after
        assertThat(once.reviewSkipStreak).isEqualTo(1)
        assertThat(Rules.autoArchive(once, clock.now(), today, clock)).isNull()
        val twice = Rules.reviewSkipped(once, today.plusDays(1), shownOnDay = true, decidedOnDay = false)!!.after
        val archived = Rules.autoArchive(twice, clock.now(), today, clock)!!.after
        assertThat(archived.status).isEqualTo(TaskStatus.ARCHIVED)
        assertThat(archived.archiveReason).isEqualTo(ArchiveReason.TTL_SKIPS)
        assertThat(archived.inReview).isFalse()
        assertThat(archived.reviewSkipStreak).isEqualTo(0)
    }

    @Test
    fun `R4 never archives a task with a future deadline`() {
        val task = aTask(inReview = true, reviewSkipStreak = 2, deadline = Deadline(date("2026-10-20")))
        assertThat(Rules.autoArchive(task, clock.now(), today, clock)).isNull()
        val overdue = task.copy(deadline = Deadline(date("2026-10-01")))
        assertThat(Rules.autoArchive(overdue, clock.now(), today, clock)).isNotNull()
    }

    @Test
    fun `R5 puts an inactive project into review`() {
        val project = aProject(lastActivityAt = clock.now().minus(Duration.ofDays(30)))
        assertThat(Rules.projectInactive(project, today, clock.now(), settings, clock)!!.after.inReview).isTrue()
        assertThat(Rules.projectInactive(project.copy(lastActivityAt = clock.now()), today, clock.now(), settings, clock)).isNull()
    }

    @Test
    fun `R6 no next step is derived from active tasks`() {
        val project = aProject()
        assertThat(Rules.hasNoNextStep(project, emptyList())).isTrue()
        assertThat(Rules.hasNoNextStep(project, listOf(aTask(projectId = project.id, status = TaskStatus.DONE)))).isTrue()
        assertThat(Rules.hasNoNextStep(project, listOf(aTask(projectId = project.id)))).isFalse()
    }

    @Test
    fun `R8 suggests triage above the threshold`() {
        assertThat(Rules.inboxOverflow(20, settings)).isFalse()
        assertThat(Rules.inboxOverflow(21, settings)).isTrue()
    }
}
