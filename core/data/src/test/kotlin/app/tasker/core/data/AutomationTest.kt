package app.tasker.core.data

import app.tasker.core.data.command.CaptureRequest
import app.tasker.core.data.search.SearchFilters
import app.tasker.core.domain.plan.TaskTiming
import app.tasker.core.model.Actor
import app.tasker.core.model.ArchiveReason
import app.tasker.core.model.Bucket
import app.tasker.core.model.CaptureChannel
import app.tasker.core.model.EntityType
import app.tasker.core.model.EventType
import app.tasker.core.model.ReasonCode
import app.tasker.core.model.ReviewDecision
import app.tasker.core.model.ReviewKind
import app.tasker.core.model.TaskStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutomationTest {
    private val env = DataEnv()

    @After
    fun tearDown() = env.close()

    @Test
    fun `automation never moves last touched, user commands always do (TTL-1)`() = runTest {
        val inbox = env.add("разобрать почту")
        val planned = env.add("завтра позвонить")
        val today = env.add("сегодня отчёт")
        env.maintenance.recordActivity()
        val touched = listOf(inbox, planned, today).associate { it.id to env.task(it.id).lastTouchedAt }

        env.advanceDays(20)
        env.maintenance.catchUp()

        val automatic = env.allEvents().filter { it.actor != Actor.USER }
        assertThat(automatic).isNotEmpty()
        automatic.forEach { assertThat(it.reason).isNotNull() }
        touched.forEach { (id, at) -> assertThat(env.task(id).lastTouchedAt).isEqualTo(at) }

        env.tasks.confirmRelevant(inbox.id)
        assertThat(env.task(inbox.id).lastTouchedAt).isEqualTo(env.clock.now())
    }

    @Test
    fun `catch-up is idempotent`() = runTest {
        env.add("разобрать почту")
        env.add("сегодня отчёт")
        env.add("в пт до 12:00 сдать отчёт")
        env.maintenance.recordActivity()
        env.plans.accept()
        env.advanceDays(9)

        val first = env.maintenance.catchUp()
        assertThat(first.changedAnything).isTrue()
        val eventsAfterFirst = env.allEvents().size
        val second = env.maintenance.catchUp()
        assertThat(second.changedAnything).isFalse()
        assertThat(env.allEvents()).hasSize(eventsAfterFirst)
    }

    @Test
    fun `scenario 6 - stale task goes to review, is archived, found and restored for good`() = runTest {
        val task = env.add("заказать билеты")

        env.advanceDays(7)
        env.maintenance.catchUp()
        var stored = env.task(task.id)
        assertThat(stored.inReview).isTrue()
        assertThat(stored.status).isEqualTo(TaskStatus.OPEN)
        val entered = env.events(task.id).single { it.type == EventType.REVIEW_ENTERED }
        assertThat(entered.reason?.code).isEqualTo(ReasonCode.TTL_EXPIRED)
        assertThat(entered.reason?.params?.get("days")).isEqualTo("7")

        env.review.decideTask(task.id, ReviewDecision.ARCHIVE)
        assertThat(env.task(task.id).status).isEqualTo(TaskStatus.ARCHIVED)

        env.advanceDays(30)
        env.maintenance.catchUp()
        val found = env.search.searchOnce("билет")
        assertThat(found.map { it.id }).containsExactly(task.id)
        assertThat(found.single().status).isEqualTo(TaskStatus.ARCHIVED)

        env.tasks.restore(task.id)
        stored = env.task(task.id)
        assertThat(stored.status).isEqualTo(TaskStatus.OPEN)
        assertThat(stored.inReview).isFalse()
        assertThat(stored.reviewSkipStreak).isEqualTo(0)

        env.advanceDays(1)
        env.maintenance.catchUp()
        stored = env.task(task.id)
        assertThat(stored.status).isEqualTo(TaskStatus.OPEN)
        assertThat(stored.inReview).isFalse()
    }

    @Test
    fun `two skipped review days archive the task unless its deadline is ahead`() = runTest {
        val plain = env.add("старая идея")
        val withDeadline = env.add("продлить паспорт до 30.11")
        env.advanceDays(8)
        env.maintenance.catchUp()
        assertThat(env.task(plain.id).inReview).isTrue()
        assertThat(env.task(withDeadline.id).inReview).isTrue()

        repeat(2) {
            val session = env.review.startSession(ReviewKind.RELEVANCE)
            env.review.cardShown(session, EntityType.TASK, plain.id)
            env.review.cardShown(session, EntityType.TASK, withDeadline.id)
            env.advanceDays(1)
            env.maintenance.catchUp()
        }

        val archived = env.task(plain.id)
        assertThat(archived.status).isEqualTo(TaskStatus.ARCHIVED)
        assertThat(archived.archiveReason).isEqualTo(ArchiveReason.TTL_SKIPS)
        assertThat(archived.inReview).isFalse()
        assertThat(env.task(withDeadline.id).status).isEqualTo(TaskStatus.OPEN)
        assertThat(env.task(withDeadline.id).reviewSkipStreak).isEqualTo(2)
    }

    @Test
    fun `a skipped card counts once per day and a decision resets the streak`() = runTest {
        val task = env.add("старая идея")
        env.advanceDays(8)
        env.maintenance.catchUp()
        val session = env.review.startSession(ReviewKind.RELEVANCE)
        env.review.cardShown(session, EntityType.TASK, task.id)
        env.review.cardShown(session, EntityType.TASK, task.id)
        val second = env.review.startSession(ReviewKind.RELEVANCE)
        env.review.cardShown(second, EntityType.TASK, task.id)
        env.advanceDays(1)
        env.maintenance.catchUp()
        assertThat(env.task(task.id).reviewSkipStreak).isEqualTo(1)

        env.review.decideTask(task.id, ReviewDecision.RELEVANT)
        assertThat(env.task(task.id).reviewSkipStreak).isEqualTo(0)
        assertThat(env.task(task.id).inReview).isFalse()
    }

    @Test
    fun `scenario 7 - two weeks away give one catch-up pass and at most one postpone`() = runTest {
        val deadline = env.add("в пт до 18:00 сдать отчёт")
        val todayTask = env.add("сегодня купить продукты")
        val planned = env.add("завтра позвонить врачу")
        val inbox = env.add("прочитать книгу")
        env.maintenance.recordActivity()
        env.plans.accept()

        env.advanceDays(14)
        val report = env.maintenance.catchUp()
        assertThat(report.daysClosed).hasSize(14)

        val now = env.clock.now()
        val today = env.clock.today()
        val zone = env.clock.zone()
        val tasks = listOf(deadline, todayTask, planned, inbox).map { env.task(it.id) }
        assertThat(tasks.filter { TaskTiming.isOverdue(it, now, today, zone) }.map { it.id }).containsExactly(deadline.id)
        tasks.forEach { assertThat(it.postponeCount).isAtMost(1) }
        assertThat(env.task(planned.id).postponeCount).isEqualTo(1)
        assertThat(env.task(todayTask.id).postponeCount).isEqualTo(1)
        assertThat(env.task(inbox.id).inReview).isTrue()
        tasks.forEach { assertThat(it.status).isNotEqualTo(TaskStatus.ARCHIVED) }

        assertThat(env.maintenance.catchUp().changedAnything).isFalse()
    }

    @Test
    fun `undone automation is not repeated by the next catch-up`() = runTest {
        val task = env.add("разобрать почту")
        val planned = env.add("завтра позвонить")
        env.advanceDays(8)
        val report = env.maintenance.catchUp()
        assertThat(env.task(task.id).inReview).isTrue()
        assertThat(env.task(planned.id).postponeCount).isEqualTo(1)

        for (batch in report.batches) env.undo.undoBatch(batch)
        assertThat(env.task(task.id).inReview).isFalse()
        assertThat(env.task(planned.id).postponeCount).isEqualTo(0)
        assertThat(env.task(planned.id).planDateRolled).isEqualTo(planned.planDate)

        val again = env.maintenance.catchUp()
        assertThat(again.changedAnything).isFalse()
        assertThat(env.task(task.id).inReview).isFalse()
        assertThat(env.task(planned.id).postponeCount).isEqualTo(0)
    }

    @Test
    fun `undo of an automatic archive brings the task back outside review for good`() = runTest {
        val task = env.add("старая идея")
        env.advanceDays(8)
        env.maintenance.catchUp()
        repeat(2) {
            val session = env.review.startSession(ReviewKind.RELEVANCE)
            env.review.cardShown(session, EntityType.TASK, task.id)
            env.advanceDays(1)
            env.maintenance.catchUp()
        }
        val archive = env.events(task.id).single { it.type == EventType.ARCHIVED }
        env.undo.undoEntity(archive.batchId, task.id)
        val restored = env.task(task.id)
        assertThat(restored.status).isEqualTo(TaskStatus.OPEN)
        assertThat(restored.inReview).isFalse()
        assertThat(restored.reviewSkipStreak).isEqualTo(0)
        assertThat(env.maintenance.catchUp().changedAnything).isFalse()
        assertThat(env.task(task.id).status).isEqualTo(TaskStatus.OPEN)
    }

    @Test
    fun `undo keeps fields the user changed since and reports them`() = runTest {
        val task = env.add("разобрать почту")
        env.advanceDays(8)
        val report = env.maintenance.catchUp()
        env.tasks.move(task.id, Bucket.WEEK)
        val outcome = env.undo.undoBatch(report.batches.single()).value
        assertThat(outcome.conflicts.single().fields).contains("in_review")
        assertThat(env.task(task.id).bucket).isEqualTo(Bucket.WEEK)
    }

    @Test
    fun `inactive project goes to review and is never archived automatically`() = runTest {
        val outcome = env.capture.capture(CaptureRequest("набросать план #ремонт", CaptureChannel.BAR)).value
        val projectId = checkNotNull(outcome.createdProject).id
        env.tasks.complete(outcome.task.id)
        env.advanceDays(31)
        env.maintenance.catchUp()
        val project = checkNotNull(env.repo.project(projectId))
        assertThat(project.inReview).isTrue()
        assertThat(project.status).isEqualTo(app.tasker.core.model.ProjectStatus.ACTIVE)
        val event = env.events(projectId).single { it.type == EventType.REVIEW_ENTERED }
        assertThat(event.reason?.code).isEqualTo(ReasonCode.PROJECT_INACTIVE)
    }

    @Test
    fun `search filters include the archive and mark it`() = runTest {
        val open = env.add("купить молоко")
        val archived = env.add("купить хлеб")
        env.tasks.archive(archived.id)
        val all = env.search.searchOnce("купил")
        assertThat(all.map { it.id }).containsExactly(open.id, archived.id)
        assertThat(all.last().status).isEqualTo(TaskStatus.ARCHIVED)
        val onlyOpen = env.search.searchOnce("купить", SearchFilters(statuses = setOf(TaskStatus.OPEN)))
        assertThat(onlyOpen.map { it.id }).containsExactly(open.id)
    }
}
