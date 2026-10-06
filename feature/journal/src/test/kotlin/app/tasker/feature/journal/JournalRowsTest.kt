package app.tasker.feature.journal

import app.tasker.core.data.command.UndoConflict
import app.tasker.core.data.command.UndoOutcome
import app.tasker.core.data.repository.JournalBatch
import app.tasker.core.data.repository.JournalEntry
import app.tasker.core.model.Actor
import app.tasker.core.model.EntityType
import app.tasker.core.model.Event
import app.tasker.core.model.EventType
import app.tasker.core.model.Reason
import app.tasker.core.model.ReasonCode
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.Test

class JournalRowsTest {
    private val window: Duration = Duration.ofDays(30)

    private fun entry(
        batchId: String,
        entityId: String,
        createdAt: Instant,
        type: EntityType = EntityType.TASK,
        actor: Actor = Actor.RULE,
        undone: Boolean = false,
        undoUntil: Instant? = createdAt.plus(window),
    ) = JournalEntry(
        event = Event(
            id = "$batchId/$entityId",
            batchId = batchId,
            entityType = type,
            entityId = entityId,
            actor = actor,
            type = if (actor == Actor.AI) EventType.AI_FILLED else EventType.REVIEW_ENTERED,
            changes = emptyList(),
            reason = if (actor == Actor.AI) {
                Reason(ReasonCode.AI_ENRICHED, mapOf("fields" to "ESTIMATE"))
            } else {
                Reason(ReasonCode.TTL_EXPIRED, mapOf("days" to "7", "bucket" to "WEEK"))
            },
            createdAt = createdAt,
            undoUntil = undoUntil,
            undoneAt = if (undone) createdAt.plusSeconds(60) else null,
        ),
        title = "Task $entityId",
    )

    private fun batch(id: String, at: String, day: LocalDate, actor: Actor = Actor.RULE, vararg entities: String): JournalBatch {
        val createdAt = Instant.parse(at)
        return JournalBatch(id, actor, createdAt, day, entities.map { entry(id, it, createdAt, actor = actor) })
    }

    @Test
    fun `days go newest first, rule batches before the folded AI fills of the day`() {
        val monday = date("2026-10-05")
        val tuesday = date("2026-10-06")
        val batches = listOf(
            batch("rule-old", "2026-10-05T05:00:00Z", monday, Actor.RULE, "a", "b"),
            batch("ai-1", "2026-10-06T09:00:00Z", tuesday, Actor.AI, "c"),
            batch("rule-new", "2026-10-06T05:00:00Z", tuesday, Actor.RULE, "d"),
            batch("ai-2", "2026-10-06T10:00:00Z", tuesday, Actor.AI, "c"),
        )

        val rows = JournalRows.build(batches, expandedAiDays = emptySet())

        assertThat(rows.map { it.key }).containsExactly(
            "day-2026-10-06",
            "batch-rule-new",
            "ai-2026-10-06",
            "day-2026-10-05",
            "batch-rule-old",
        ).inOrder()
        val group = rows[2] as JournalRow.AiGroup
        assertThat(group.expanded).isFalse()
        assertThat(group.batches.map { it.batchId }).containsExactly("ai-2", "ai-1").inOrder()
        // The same task filled twice counts once.
        assertThat(group.taskCount).isEqualTo(1)
    }

    @Test
    fun `an opened AI group is followed by its batches`() {
        val tuesday = date("2026-10-06")
        val batches = listOf(
            batch("ai-1", "2026-10-06T09:00:00Z", tuesday, Actor.AI, "c"),
            batch("ai-2", "2026-10-06T10:00:00Z", tuesday, Actor.AI, "e"),
        )

        val rows = JournalRows.build(batches, expandedAiDays = setOf(tuesday))

        assertThat(rows.map { it.key }).containsExactly("day-2026-10-06", "ai-2026-10-06", "batch-ai-2", "batch-ai-1").inOrder()
        assertThat((rows[1] as JournalRow.AiGroup).expanded).isTrue()
        assertThat(rows.map { it.key }).containsNoDuplicates()
    }

    @Test
    fun `no batches, no rows`() {
        assertThat(JournalRows.build(emptyList(), emptySet())).isEmpty()
    }

    @Test
    fun `undo is available within 30 days and gone after`() {
        val createdAt = Instant.parse("2026-09-01T05:00:00Z")
        val batch =
            JournalBatch("b", Actor.RULE, createdAt, date("2026-09-01"), listOf(entry("b", "a", createdAt), entry("b", "b", createdAt)))

        assertThat(JournalRows.undoState(batch, createdAt.plus(Duration.ofDays(29)))).isEqualTo(UndoState.AVAILABLE)
        assertThat(JournalRows.undoState(batch, createdAt.plus(window))).isEqualTo(UndoState.EXPIRED)
        assertThat(JournalRows.undoState(batch.entries.first(), createdAt.plus(Duration.ofDays(31)))).isEqualTo(UndoState.EXPIRED)
    }

    @Test
    fun `undone entries are reported per entry and for the batch`() {
        val createdAt = Instant.parse("2026-10-06T05:00:00Z")
        val now = createdAt.plus(Duration.ofDays(1))
        val partly = JournalBatch(
            "b",
            Actor.RULE,
            createdAt,
            date("2026-10-06"),
            listOf(entry("b", "a", createdAt, undone = true), entry("b", "b", createdAt)),
        )
        val whole = partly.copy(entries = partly.entries.map { entry("b", it.event.entityId, createdAt, undone = true) })

        assertThat(JournalRows.undoState(partly, now)).isEqualTo(UndoState.PARTLY_UNDONE)
        assertThat(JournalRows.undoState(partly.entries[0], now)).isEqualTo(UndoState.UNDONE)
        assertThat(JournalRows.undoState(partly.entries[1], now)).isEqualTo(UndoState.AVAILABLE)
        assertThat(JournalRows.undoState(whole, now)).isEqualTo(UndoState.UNDONE)
    }

    @Test
    fun `an event without an undo window cannot be undone`() {
        val createdAt = Instant.parse("2026-10-06T05:00:00Z")
        val entry = entry("b", "a", createdAt, undoUntil = null)

        assertThat(JournalRows.undoState(entry, createdAt)).isEqualTo(UndoState.EXPIRED)
    }

    @Test
    fun `counts distinct tasks and projects of a batch`() {
        val createdAt = Instant.parse("2026-10-06T05:00:00Z")
        val batch = JournalBatch(
            "b",
            Actor.RULE,
            createdAt,
            date("2026-10-06"),
            listOf(
                entry("b", "t1", createdAt),
                entry("b", "t2", createdAt),
                entry("b", "p1", createdAt, type = EntityType.PROJECT),
            ),
        )

        assertThat(JournalRows.counts(batch)).isEqualTo(EntityCounts(tasks = 2, projects = 1))
    }

    @Test
    fun `undo outcome is reported as nothing to undo, kept fields or done`() {
        val conflict = UndoConflict(EntityType.TASK, "t1", listOf("bucket"))

        assertThat(JournalRows.report(UndoOutcome(0, emptyList()))).isEqualTo(UndoReport.NOTHING_TO_UNDO)
        assertThat(JournalRows.report(UndoOutcome(2, listOf(conflict)))).isEqualTo(UndoReport.CONFLICTS)
        assertThat(JournalRows.report(UndoOutcome(2, emptyList()))).isEqualTo(UndoReport.UNDONE)
    }
}
