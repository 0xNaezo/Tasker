package app.tasker.feature.journal

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tasker.core.data.repository.JournalBatch
import app.tasker.core.data.repository.JournalEntry
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.model.Actor
import app.tasker.core.model.EntityType
import app.tasker.core.model.Event
import app.tasker.core.model.EventType
import app.tasker.core.model.Reason
import app.tasker.core.model.ReasonCode
import app.tasker.core.model.TaskId
import app.tasker.core.testing.date
import app.tasker.core.ui.DayContext
import app.tasker.core.ui.LocalDayContext
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = date("2026-10-06")
    private val createdAt = Instant.parse("2026-10-06T05:00:00Z")

    private class Calls {
        val toggled = mutableListOf<JournalBatch>()
        val aiDays = mutableListOf<LocalDate>()
        val batchUndos = mutableListOf<JournalBatch>()
        val entryUndos = mutableListOf<JournalEntry>()
        val opened = mutableListOf<TaskId>()

        fun actions() = JournalActions(
            onToggle = { toggled += it },
            onToggleAi = { aiDays += it },
            onUndoBatch = { batchUndos += it },
            onUndoEntry = { _, entry -> entryUndos += entry },
            onDismissKept = {},
            onOpenTask = { opened += it },
        )
    }

    private fun entry(entityId: String, title: String, actor: Actor = Actor.RULE, days: String = "7") = JournalEntry(
        event = Event(
            id = "e-$entityId",
            batchId = "b",
            entityType = EntityType.TASK,
            entityId = entityId,
            actor = actor,
            type = if (actor == Actor.AI) EventType.AI_FILLED else EventType.REVIEW_ENTERED,
            changes = emptyList(),
            reason = if (actor == Actor.AI) {
                Reason(ReasonCode.AI_ENRICHED, mapOf("fields" to "ESTIMATE"))
            } else {
                Reason(ReasonCode.TTL_EXPIRED, mapOf("days" to days, "bucket" to "WEEK"))
            },
            createdAt = createdAt,
            undoUntil = createdAt.plus(Duration.ofDays(30)),
        ),
        title = title,
    )

    private val review = JournalBatch("b", Actor.RULE, createdAt, today, listOf(entry("t1", "Call the bank"), entry("t2", "Fix the bike")))

    private fun showCard(batch: JournalBatch, expanded: Boolean = false, now: Instant = createdAt.plusSeconds(60)): Calls {
        val calls = Calls()
        compose.setContent {
            TaskerTheme {
                BatchCard(batch, DayContext(today, now, ZoneId.of("UTC")), expanded = expanded, busy = false, actions = calls.actions())
            }
        }
        return calls
    }

    @Test
    fun `a batch says when, what, why and with which tasks, and is undone as a whole`() {
        val calls = showCard(review)

        compose.onNodeWithText("· Rules", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Sent to review · 2 tasks").assertIsDisplayed()
        compose.onNodeWithText("Not touched for 7 days · Week").assertIsDisplayed()
        compose.onNodeWithText("Call the bank").performClick()
        compose.onNodeWithText("Undo all").performClick()
        compose.onNodeWithText("Details").performClick()

        assertThat(calls.opened).containsExactly("t1")
        assertThat(calls.batchUndos).containsExactly(review)
        assertThat(calls.toggled).containsExactly(review)
    }

    @Test
    fun `an expanded batch undoes one task and shows different reasons per task`() {
        val batch = review.copy(entries = listOf(entry("t1", "Call the bank"), entry("t2", "Fix the bike", days = "14")))
        val calls = showCard(batch, expanded = true)

        compose.onNodeWithText("Not touched for 14 days · Week").assertIsDisplayed()
        compose.onAllNodesWithText("Undo")[1].performClick()
        compose.onNodeWithText("Hide details").assertIsDisplayed()

        assertThat(calls.entryUndos.map { it.event.entityId }).containsExactly("t2")
    }

    @Test
    fun `after 30 days the batch stays visible without undo`() {
        showCard(review, now = createdAt.plus(Duration.ofDays(31)))

        compose.onNodeWithText("Older than 30 days, can't be undone").assertIsDisplayed()
        compose.onNodeWithText("Undo all").assertDoesNotExist()
    }

    @Test
    fun `an undone batch says so once`() {
        val undone = review.copy(entries = review.entries.map { it.copy(event = it.event.copy(undoneAt = createdAt.plusSeconds(5))) })
        showCard(undone)

        compose.onAllNodesWithText("Undone").assertCountEquals(1)
        compose.onNodeWithText("Undo all").assertDoesNotExist()
    }

    @Test
    fun `a partly undone batch marks the undone task and still undoes the rest`() {
        val partly = review.copy(
            entries = listOf(review.entries[0].copy(event = review.entries[0].event.copy(undoneAt = createdAt)), review.entries[1]),
        )
        showCard(partly)

        compose.onNodeWithText("Partly undone").assertIsDisplayed()
        compose.onAllNodesWithText("Undone").assertCountEquals(1)
        compose.onNodeWithText("Undo all").assertIsDisplayed()
    }

    @Test
    fun `the day's AI fills are folded into one row that opens on tap`() {
        val ai = JournalBatch("ai", Actor.AI, createdAt, today, listOf(entry("t3", "Book tickets", actor = Actor.AI)))
        val calls = Calls()
        compose.setContent {
            TaskerTheme {
                CompositionLocalProvider(LocalDayContext provides DayContext(today, createdAt, ZoneId.of("UTC"))) {
                    JournalContent(
                        state = JournalUiState(loading = false, rows = JournalRows.build(listOf(review, ai), emptySet())),
                        actions = calls.actions(),
                        onBack = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Book tickets").assertDoesNotExist()
        compose.onNodeWithText("AI filled in 1 task").performClick()

        assertThat(calls.aiDays).containsExactly(today)
    }

    @Test
    fun `an empty journal explains what will appear`() {
        compose.setContent {
            TaskerTheme {
                JournalContent(state = JournalUiState(loading = false), actions = Calls().actions(), onBack = {})
            }
        }

        compose.onNodeWithText("Nothing here yet").assertIsDisplayed()
    }
}
