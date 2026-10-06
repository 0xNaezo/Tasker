package app.tasker.widget

import app.tasker.core.data.plan.DayViews
import app.tasker.core.domain.capacity.Capacity
import app.tasker.core.model.AppSettings
import app.tasker.core.model.DayPlan
import app.tasker.core.model.DayPlanItem
import app.tasker.core.model.Deadline
import app.tasker.core.model.PlanItemOrigin
import app.tasker.core.model.PlanItemOutcome
import app.tasker.core.model.PlanState
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import app.tasker.core.testing.aTask
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import org.junit.Test

class WidgetStateTest {
    private val day = date("2026-10-06")
    private val now = Instant.parse("2026-10-06T09:00:00Z")
    private val capacity = Capacity(
        day = day,
        window = null,
        workingMin = 480,
        busyMin = 0,
        freeMin = 480,
        capacityMin = 400,
        isWorkDay = true,
        calendarAvailable = false,
    )

    private fun view(active: List<Task>, plan: DayPlan? = null, done: List<Task> = emptyList()) = DayViews.build(
        day = day,
        now = now,
        zone = ZoneId.of("Europe/Kyiv"),
        settings = AppSettings(),
        capacity = capacity,
        plan = plan,
        activeTasks = active,
        planTasks = (active + done).associateBy { it.id },
        snapshots = emptyMap(),
        reviewCount = 0,
        inboxCount = 0,
    )

    private fun item(task: Task, position: Int, outcome: PlanItemOutcome = PlanItemOutcome.PENDING) = DayPlanItem(
        date = day,
        taskId = task.id,
        position = position,
        minutes = 30,
        origin = PlanItemOrigin.MANUAL,
        outcome = outcome,
    )

    @Test
    fun `without an accepted plan the candidates follow the work in progress`() {
        val planned = aTask(title = "Planned", planDate = day)
        val overdue = aTask(title = "Overdue", deadline = Deadline(day.minusDays(2)))
        val paused = aTask(title = "Paused", status = TaskStatus.PAUSED)
        val working = aTask(title = "Working", status = TaskStatus.IN_PROGRESS)

        val state = WidgetState.of(view(listOf(planned, overdue, paused, working)))

        assertThat(state.rows.map { it.title to it.mark }).containsExactly(
            "Working" to RowMark.IN_PROGRESS,
            "Paused" to RowMark.PAUSED,
            "Overdue" to RowMark.OVERDUE,
            "Planned" to RowMark.NONE,
        ).inOrder()
        assertThat(state.planAccepted).isFalse()
    }

    @Test
    fun `an accepted plan shows its unfinished items and counts the done ones`() {
        val first = aTask(title = "First", planDate = day)
        val finished = aTask(title = "Finished", status = TaskStatus.DONE).copy(completedAt = now)
        val second = aTask(title = "Second")
        val notInPlan = aTask(title = "Not in the plan", planDate = day)
        val plan = DayPlan(
            date = day,
            state = PlanState.ACCEPTED,
            createdAt = now,
            acceptedAt = now,
            items = listOf(item(first, 0), item(finished, 1, PlanItemOutcome.DONE), item(second, 2)),
        )

        val state = WidgetState.of(view(listOf(first, second, notInPlan), plan, done = listOf(finished)))

        assertThat(state.rows.map { it.title }).containsExactly("First", "Second").inOrder()
        assertThat(state.planAccepted).isTrue()
        assertThat(state.planDone).isEqualTo(1)
        assertThat(state.planTotal).isEqualTo(3)
    }

    @Test
    fun `a task in progress from the plan is listed once`() {
        val working = aTask(title = "Working", status = TaskStatus.IN_PROGRESS)
        val plan = DayPlan(date = day, state = PlanState.ACCEPTED, createdAt = now, items = listOf(item(working, 0)))

        val state = WidgetState.of(view(listOf(working), plan))

        assertThat(state.rows.map { it.title to it.mark }).containsExactly("Working" to RowMark.IN_PROGRESS)
    }

    @Test
    fun `the list is capped`() {
        val tasks = List(WidgetState.MAX_ROWS + 5) { aTask(planDate = day) }

        assertThat(WidgetState.of(view(tasks)).rows).hasSize(WidgetState.MAX_ROWS)
    }
}
