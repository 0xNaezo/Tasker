package app.tasker.core.data

import app.tasker.core.data.command.CaptureRequest
import app.tasker.core.data.command.FieldUpdate
import app.tasker.core.data.command.InvalidCommandException
import app.tasker.core.data.command.SharedOrigin
import app.tasker.core.data.command.SnapshotInput
import app.tasker.core.data.command.TaskEdit
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.domain.status.InvalidTransitionException
import app.tasker.core.model.Actor
import app.tasker.core.model.Bucket
import app.tasker.core.model.CaptureChannel
import app.tasker.core.model.Estimate
import app.tasker.core.model.EventType
import app.tasker.core.model.FieldSource
import app.tasker.core.model.PlanItemOrigin
import app.tasker.core.model.PlanItemOutcome
import app.tasker.core.model.SourceKind
import app.tasker.core.model.TaskField
import app.tasker.core.model.TaskStatus
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommandsTest {
    private val env = DataEnv()

    @After
    fun tearDown() = env.close()

    @Test
    fun `capture parses fields, creates the project and writes one CREATED event`() = runTest {
        val outcome = env.capture.capture(CaptureRequest("пт до 18:00 сдать отчёт #client M", CaptureChannel.BAR)).value
        val task = env.task(outcome.task.id)

        assertThat(task.title).isEqualTo("сдать отчёт")
        assertThat(task.deadline?.date).isEqualTo(date("2026-10-09"))
        assertThat(task.deadline?.time).isEqualTo(LocalTime.of(18, 0))
        assertThat(task.deadline?.zone).isEqualTo(env.clock.zone())
        assertThat(task.estimate).isEqualTo(Estimate.M)
        assertThat(outcome.createdProject?.name).isEqualTo("client")
        assertThat(task.projectId).isEqualTo(outcome.createdProject?.id)
        assertThat(task.fieldSources[TaskField.DEADLINE]).isEqualTo(FieldSource.PARSER)
        assertThat(task.fieldSources[TaskField.TITLE]).isEqualTo(FieldSource.USER)
        assertThat(task.lastTouchedAt).isEqualTo(env.clock.now())
        val events = env.events(task.id)
        assertThat(events.map { it.type }).containsExactly(EventType.CREATED)
        assertThat(events.single().actor).isEqualTo(Actor.USER)
    }

    @Test
    fun `plain capture goes to the inbox, a date without marker is a plan date`() = runTest {
        val task = env.add("завтра позвонить маме")
        assertThat(task.isInbox).isTrue()
        assertThat(task.planDate).isEqualTo(date("2026-10-07"))
        assertThat(task.deadline).isNull()
    }

    @Test
    fun `already done capture is done and off plan`() = runTest {
        val task = env.add("уже сделал отчёт")
        assertThat(task.status).isEqualTo(TaskStatus.DONE)
        assertThat(task.offPlan).isTrue()
        assertThat(task.completedAt).isEqualTo(env.clock.now())
    }

    @Test
    fun `shared text keeps the link and the sending app as sources`() = runTest {
        val outcome = env.capture.capture(
            CaptureRequest(
                input = "почитать https://example.com/article",
                channel = CaptureChannel.SHARE,
                sharedFrom = SharedOrigin(appPackage = "org.telegram.messenger", url = null),
            ),
        ).value
        val sources = env.db.contentDao().sources(outcome.task.id)
        assertThat(sources.map { it.kind }).containsExactly(SourceKind.LINK)
        assertThat(sources.single().url).isEqualTo("https://example.com/article")
        assertThat(sources.single().appPackage).isEqualTo("org.telegram.messenger")
        assertThat(env.task(outcome.task.id).captureChannel).isEqualTo(CaptureChannel.SHARE)
    }

    @Test
    fun `status transitions follow the machine and invalid ones fail`() = runTest {
        val task = env.add("написать тесты")
        val started = env.tasks.start(task.id).value
        assertThat(started.task.status).isEqualTo(TaskStatus.IN_PROGRESS)
        assertThat(started.task.startedAt).isNotNull()

        env.tasks.pause(task.id, SnapshotInput("остановился на парсере"))
        assertThat(env.task(task.id).status).isEqualTo(TaskStatus.PAUSED)
        assertThat(env.db.contentDao().snapshots(task.id).single().text).isEqualTo("остановился на парсере")

        env.tasks.complete(task.id)
        assertThat(env.task(task.id).status).isEqualTo(TaskStatus.DONE)
        assertThrows(InvalidTransitionException::class.java) { kotlinx.coroutines.runBlocking { env.tasks.pause(task.id) } }
    }

    @Test
    fun `wip limit is soft and reported`() = runTest {
        val ids = (1..4).map { env.add("задача $it").id }
        val outcomes = ids.map { env.tasks.start(it).value }
        assertThat(outcomes.last().overLimit).isTrue()
        assertThat(outcomes.last().task.status).isEqualTo(TaskStatus.IN_PROGRESS)
        assertThat(outcomes[2].overLimit).isFalse()
    }

    @Test
    fun `snackbar undo of complete restores the previous status and today's plan item`() = runTest {
        val task = env.add("сегодня важное дело")
        env.tasks.start(task.id)
        env.plans.accept()
        assertThat(env.plans.prepare().plan.items.map { it.taskId }).contains(task.id)

        val done = env.tasks.complete(task.id)
        val itemAfterDone = env.db.planDao().item(env.clock.today().toEpochDay(), task.id)
        assertThat(itemAfterDone?.outcome).isEqualTo(PlanItemOutcome.DONE)

        val undo = env.undo.undoBatch(checkNotNull(done.batchId)).value
        assertThat(undo.conflicts).isEmpty()
        val restored = env.task(task.id)
        assertThat(restored.status).isEqualTo(TaskStatus.IN_PROGRESS)
        assertThat(restored.completedAt).isNull()
        assertThat(env.db.planDao().item(env.clock.today().toEpochDay(), task.id)?.outcome).isEqualTo(PlanItemOutcome.PENDING)
        assertThat(env.undo.undoBatch(done.batchId!!).value.nothingToUndo).isTrue()
    }

    @Test
    fun `postpone counts at most once per day and removes the plan item`() = runTest {
        val task = env.add("сегодня отчёт")
        env.plans.accept()

        env.tasks.postpone(task.id, PostponeOption.Tomorrow)
        var stored = env.task(task.id)
        assertThat(stored.planDate).isEqualTo(date("2026-10-07"))
        assertThat(stored.bucket).isEqualTo(Bucket.WEEK)
        assertThat(stored.postponeCount).isEqualTo(1)
        assertThat(env.db.planDao().item(env.clock.today().toEpochDay(), task.id)?.outcome).isEqualTo(PlanItemOutcome.REMOVED)

        env.tasks.move(task.id, Bucket.TODAY)
        env.tasks.postpone(task.id, PostponeOption.Week)
        stored = env.task(task.id)
        assertThat(stored.postponeCount).isEqualTo(1)
        assertThat(stored.deadline).isNull()
    }

    @Test
    fun `postponing into the past is rejected`() = runTest {
        val task = env.add("отчёт")
        assertThrows(InvalidCommandException::class.java) {
            kotlinx.coroutines.runBlocking { env.tasks.postpone(task.id, PostponeOption.OnDate(date("2026-10-01"))) }
        }
    }

    @Test
    fun `task sent to today after acceptance is appended to the plan`() = runTest {
        env.add("сегодня первое")
        env.plans.accept()
        val late = env.add("второе")
        env.tasks.move(late.id, Bucket.TODAY)

        val item = env.db.planDao().item(env.clock.today().toEpochDay(), late.id)
        assertThat(item?.origin).isEqualTo(PlanItemOrigin.LATE_ADD)
        assertThat(item?.outcome).isEqualTo(PlanItemOutcome.PENDING)
    }

    @Test
    fun `accepting the plan is not a touch`() = runTest {
        val task = env.add("сегодня задача")
        val touched = env.task(task.id).lastTouchedAt
        env.time.advanceHours(1)
        env.plans.accept()
        assertThat(env.task(task.id).lastTouchedAt).isEqualTo(touched)
    }

    @Test
    fun `edit marks fields as the user's choice and keeps history per field`() = runTest {
        val task = env.add("отчёт M")
        env.tasks.edit(task.id, TaskEdit(estimate = FieldUpdate.Set(Estimate.L), title = FieldUpdate.Set("годовой отчёт")))
        val stored = env.task(task.id)
        assertThat(stored.estimate).isEqualTo(Estimate.L)
        assertThat(stored.fieldSources[TaskField.ESTIMATE]).isEqualTo(FieldSource.USER)
        val update = env.events(task.id).single { it.type == EventType.UPDATED }
        assertThat(update.changes.map { it.field }).containsAtLeast("title", "estimate")
    }

    @Test
    fun `restore returns the task to its bucket and an archived project`() = runTest {
        val task = env.add("на неделе подготовить слайды #доклад")
        env.tasks.archive(task.id)
        val projectId = checkNotNull(task.projectId)
        env.projects.archive(projectId)
        env.tasks.restore(task.id)
        val restored = env.task(task.id)
        assertThat(restored.status).isEqualTo(TaskStatus.OPEN)
        assertThat(restored.bucket).isEqualTo(Bucket.WEEK)
        assertThat(env.repo.project(projectId)?.status).isEqualTo(app.tasker.core.model.ProjectStatus.ACTIVE)
    }

    @Test
    fun `split turns the task into a project with steps and archives the original`() = runTest {
        val task = env.add("сегодня переезд пт до 18:00")
        env.plans.accept()
        val outcome = env.tasks.split(task.id, listOf("упаковать вещи", "заказать машину", "- перевезти")).value

        assertThat(outcome.project.name).isEqualTo(env.task(task.id).title)
        assertThat(outcome.steps.map { it.title }).containsExactly("упаковать вещи", "заказать машину", "перевезти").inOrder()
        assertThat(outcome.steps.first().bucket).isEqualTo(Bucket.TODAY)
        assertThat(outcome.steps[1].bucket).isEqualTo(Bucket.WEEK)
        assertThat(outcome.steps.last().deadline).isEqualTo(env.task(task.id).deadline)
        assertThat(env.task(task.id).status).isEqualTo(TaskStatus.ARCHIVED)
        assertThat(env.task(task.id).archiveReason).isEqualTo(app.tasker.core.model.ArchiveReason.SPLIT)
        val today = env.clock.today().toEpochDay()
        assertThat(env.db.planDao().item(today, outcome.steps.first().id)?.outcome).isEqualTo(PlanItemOutcome.PENDING)
        assertThat(env.db.planDao().item(today, task.id)?.outcome).isEqualTo(PlanItemOutcome.REMOVED)
    }

    @Test
    fun `archiving a project keeps tasks with a future deadline open and detached`() = runTest {
        val keep = env.add("пт до 18:00 сдать отчёт #client")
        val projectId = checkNotNull(keep.projectId)
        val drop = env.capture.capture(CaptureRequest("старая идея", CaptureChannel.BAR, defaultProjectId = projectId)).value.task

        val outcome = env.projects.archive(projectId).value
        assertThat(outcome.archived).containsExactly(drop.id)
        assertThat(outcome.detached).containsExactly(keep.id)
        assertThat(env.task(keep.id).status).isEqualTo(TaskStatus.OPEN)
        assertThat(env.task(keep.id).projectId).isNull()
        assertThat(env.task(drop.id).status).isEqualTo(TaskStatus.ARCHIVED)

        env.projects.restore(projectId, restoreTasks = true)
        assertThat(env.task(drop.id).status).isEqualTo(TaskStatus.OPEN)
    }

    @Test
    fun `permanent delete removes the task, its index and history`() = runTest {
        val task = env.add("удалить меня")
        env.tasks.deletePermanently(task.id)
        assertThat(env.repo.task(task.id)).isNull()
        assertThat(env.events(task.id)).isEmpty()
        assertThat(env.search.searchOnce("удалить")).isEmpty()
    }
}
