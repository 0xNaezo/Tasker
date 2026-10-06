package app.tasker.core.domain

import app.tasker.core.domain.plan.AutoPlanner
import app.tasker.core.domain.plan.Candidate
import app.tasker.core.domain.plan.CandidateBuilder
import app.tasker.core.domain.plan.CandidateReason
import app.tasker.core.domain.plan.PlanChange
import app.tasker.core.domain.plan.TaskTiming
import app.tasker.core.model.AppSettings
import app.tasker.core.model.Bucket
import app.tasker.core.model.CandidateGroup
import app.tasker.core.model.Deadline
import app.tasker.core.model.Estimate
import app.tasker.core.model.TaskStatus
import app.tasker.core.testing.TestClock
import app.tasker.core.testing.aTask
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CandidatesAndPlannerTest {
    private val time = TestClock()
    private val clock = time.clock
    private val today = date("2026-10-06")
    private val settings = AppSettings()

    private fun build(vararg tasks: app.tasker.core.model.Task) =
        CandidateBuilder.build(tasks.toList(), today, clock.now(), clock.zone(), settings)

    @Test
    fun `candidates are grouped and ordered as in PLN-4`() {
        val overdue = aTask(title = "overdue", deadline = Deadline(date("2026-10-05")))
        val overdueTimed = aTask(title = "overdue timed", deadline = Deadline(today, LocalTime.of(9, 0), clock.zone()))
        val soon = aTask(title = "soon", deadline = Deadline(date("2026-10-08")))
        val later = aTask(title = "later", deadline = Deadline(date("2026-10-09")), bucket = Bucket.WEEK, position = 2)
        val inProgress = aTask(title = "in progress", status = TaskStatus.IN_PROGRESS)
        val paused = aTask(title = "paused", status = TaskStatus.PAUSED)
        val passed = aTask(title = "passed", planDate = date("2026-10-05"))
        val todayBucket = aTask(title = "today", bucket = Bucket.TODAY)
        val week = aTask(title = "week", bucket = Bucket.WEEK, position = 1)
        val someday = aTask(title = "someday", bucket = Bucket.SOMEDAY)
        val inbox = aTask(title = "inbox")
        val done = aTask(title = "done", status = TaskStatus.DONE, bucket = Bucket.TODAY)
        val futureWeek = aTask(title = "future", bucket = Bucket.WEEK, planDate = date("2026-10-09"))

        val candidates =
            build(overdue, overdueTimed, soon, later, inProgress, paused, passed, todayBucket, week, someday, inbox, done, futureWeek)

        assertThat(candidates.map { it.task.title }).containsExactly(
            "overdue", "overdue timed", "soon", "in progress", "paused", "passed", "today", "week", "later",
        ).inOrder()
        assertThat(candidates.first { it.task == passed }.reason).isEqualTo(CandidateReason.PlanDatePassed(date("2026-10-05")))
        assertThat(candidates.first { it.task == week }.group).isEqualTo(CandidateGroup.WEEK)
    }

    @Test
    fun `passed plan date is not overdue but stays a candidate for today`() {
        val task = aTask(planDate = date("2026-10-01"))
        assertThat(TaskTiming.isOverdue(task, clock.now(), today, clock.zone())).isFalse()
        assertThat(build(task).single().group).isEqualTo(CandidateGroup.PLANNED)
    }

    @Test
    fun `example from the plan - first fit selection`() {
        val tasks = listOf(
            aTask(title = "L overdue", estimate = Estimate.L, deadline = Deadline(date("2026-10-01"))),
            aTask(title = "M1", estimate = Estimate.M, bucket = Bucket.TODAY, position = 1),
            aTask(title = "M2", estimate = Estimate.M, bucket = Bucket.TODAY, position = 2),
            aTask(title = "S", estimate = Estimate.S, bucket = Bucket.TODAY, position = 3),
            aTask(title = "L", estimate = Estimate.L, bucket = Bucket.TODAY, position = 4),
        )
        val proposal = AutoPlanner.propose(build(*tasks.toTypedArray()), capacityMin = 294)
        assertThat(proposal.selected.map { it.task.title }).containsExactly("L overdue", "M1", "S").inOrder()
        assertThat(proposal.plannedMin).isEqualTo(255)
        assertThat(proposal.doesNotFit.map { it.task.title }).containsExactly("M2", "L").inOrder()
    }

    @Test
    fun `tasks in review are auto selected only from deadline groups`() {
        val reviewWeek = aTask(title = "review week", bucket = Bucket.WEEK, inReview = true)
        val reviewDeadline = aTask(title = "review deadline", deadline = Deadline(date("2026-10-07")), inReview = true)
        val proposal = AutoPlanner.propose(build(reviewWeek, reviewDeadline), capacityMin = 600)
        assertThat(proposal.selected.map { it.task.title }).containsExactly("review deadline")
        assertThat(proposal.skippedInReview.map { it.task.title }).containsExactly("review week")
    }

    @Test
    fun `auto selection never exceeds capacity and keeps order`() = runTest {
        val arbCandidate = Arb.element(15, 60, 180)
        checkAll(Arb.list(arbCandidate, 0..40), Arb.list(Arb.boolean(), 40..40), Arb.int(0..900)) { sizes, review, capacity ->
            val candidates = sizes.mapIndexed { index, minutes ->
                Candidate(
                    task = aTask(title = "t$index", inReview = review[index]),
                    group = if (index % 2 == 0) CandidateGroup.WEEK else CandidateGroup.DEADLINE_SOON,
                    reason = CandidateReason.FromWeek,
                    minutes = minutes,
                )
            }
            val proposal = AutoPlanner.propose(candidates, capacity)
            assertThat(proposal.plannedMin).isAtMost(capacity)
            val selectedIndexes = proposal.selected.map { candidates.indexOf(it) }
            assertThat(selectedIndexes).isInOrder()
            assertThat(proposal.selected + proposal.doesNotFit + proposal.skippedInReview).containsExactlyElementsIn(candidates)
            for (candidate in proposal.doesNotFit) {
                val selectedBefore = proposal.selected.filter { candidates.indexOf(it) < candidates.indexOf(candidate) }
                assertThat(selectedBefore.sumOf { it.minutes } + candidate.minutes).isGreaterThan(capacity)
            }
        }
    }

    @Test
    fun `plan change reports capacity and membership differences`() {
        val a = aTask(id = "a", bucket = Bucket.TODAY, estimate = Estimate.S)
        val b = aTask(id = "b", bucket = Bucket.TODAY, estimate = Estimate.S)
        val proposal = AutoPlanner.propose(build(a, b), capacityMin = 200)
        val change = PlanChange.between(draftCapacityMin = 294, draftTaskIds = listOf("a", "c"), proposal = proposal)
        assertThat(change.capacityChanged).isTrue()
        assertThat(change.addedTaskIds).containsExactly("b")
        assertThat(change.removedTaskIds).containsExactly("c")
    }
}
