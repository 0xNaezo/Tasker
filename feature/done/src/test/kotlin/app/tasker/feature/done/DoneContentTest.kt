package app.tasker.feature.done

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tasker.core.data.repository.DoneDay
import app.tasker.core.data.repository.DoneWeek
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.domain.summary.DaySummary
import app.tasker.core.model.Task
import app.tasker.core.model.TaskId
import app.tasker.core.model.TaskStatus
import app.tasker.core.testing.aTask
import app.tasker.core.testing.date
import app.tasker.core.ui.DayContext
import app.tasker.core.ui.LocalDayContext
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DoneContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = date("2026-10-06")

    private fun doneTask(title: String, id: String, offPlan: Boolean = false): Task =
        aTask(title = title, id = id).copy(status = TaskStatus.DONE, completedAt = Instant.parse("2026-10-06T06:00:00Z"), offPlan = offPlan)

    private fun show(state: DoneUiState, onOpenTask: (TaskId) -> Unit = {}, onReopen: (Task) -> Unit = {}, onShowEarlier: () -> Unit = {}) {
        compose.setContent {
            TaskerTheme {
                CompositionLocalProvider(
                    LocalDayContext provides DayContext(today, Instant.parse("2026-10-06T07:00:00Z"), ZoneId.of("UTC")),
                ) {
                    DoneContent(state, onOpenTask, onReopen, onShowEarlier)
                }
            }
        }
    }

    @Test
    fun `week and day carry their summaries without scores`() {
        val task = doneTask("Send the report", "t1")
        val day = DoneDay(today, listOf(task), DaySummary(today, done = 1, donePlanned = 1, postponed = 2))

        show(DoneUiState(weeks = listOf(DoneWeek(date("2026-10-05"), listOf(day)))))

        compose.onNodeWithText("This week").assertIsDisplayed()
        compose.onNodeWithText("5–11 October").assertIsDisplayed()
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onAllNodesWithText("Done: 1 (planned 1, off plan 0) · Postponed: 2").assertCountEquals(2)
    }

    @Test
    fun `the check mark marks a task as not done and the row opens it`() {
        val task = doneTask("Send the report", "t1")
        val day = DoneDay(today, listOf(task), DaySummary(today, done = 1, donePlanned = 0, postponed = 0))
        var reopened: Task? = null
        var opened: TaskId? = null

        show(
            DoneUiState(weeks = listOf(DoneWeek(date("2026-10-05"), listOf(day)))),
            onOpenTask = { opened = it },
            onReopen = { reopened = it },
        )
        compose.onNodeWithContentDescription("Mark as not done").performClick()
        compose.onNodeWithText("Send the report").performClick()

        assertThat(reopened).isEqualTo(task)
        assertThat(opened).isEqualTo("t1")
    }

    @Test
    fun `a task added as already done is labelled and a day with only postpones reads no zero`() {
        val captured = doneTask("Fixed the tap", "t2", offPlan = true)
        val yesterday = today.minusDays(1)
        val days = listOf(
            DoneDay(today, listOf(captured), DaySummary(today, done = 1, donePlanned = 0, postponed = 0)),
            DoneDay(yesterday, emptyList(), DaySummary(yesterday, done = 0, donePlanned = 0, postponed = 3)),
        )

        show(DoneUiState(weeks = listOf(DoneWeek(date("2026-10-05"), days))))

        compose.onNodeWithText("Added as already done").assertIsDisplayed()
        compose.onNodeWithText("Yesterday").assertIsDisplayed()
        compose.onNodeWithText("Postponed: 3").assertIsDisplayed()
        compose.onNodeWithText("Done: 0", substring = true).assertDoesNotExist()
    }

    @Test
    fun `an empty log explains itself and still reaches earlier weeks`() {
        var earlier = 0

        show(DoneUiState(weeks = emptyList()), onShowEarlier = { earlier++ })
        compose.onNodeWithText("Completed tasks appear here").assertIsDisplayed()
        compose.onNodeWithText("Show earlier weeks").performClick()

        assertThat(earlier).isEqualTo(1)
    }

    @Test
    fun `older weeks are titled by their dates`() {
        val old = date("2026-09-16")
        val day = DoneDay(old, listOf(doneTask("Old one", "t3")), DaySummary(old, done = 1, donePlanned = 0, postponed = 0))

        show(DoneUiState(weeks = listOf(DoneWeek(date("2026-09-14"), listOf(day)))))

        compose.onNodeWithText("14–20 September").assertIsDisplayed()
        compose.onNodeWithText("Wednesday, 16 September").assertIsDisplayed()
    }
}
