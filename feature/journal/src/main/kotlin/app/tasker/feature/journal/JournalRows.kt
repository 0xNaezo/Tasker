package app.tasker.feature.journal

import androidx.compose.runtime.Immutable
import app.tasker.core.data.command.UndoOutcome
import app.tasker.core.data.repository.JournalBatch
import app.tasker.core.data.repository.JournalEntry
import app.tasker.core.model.EntityType
import java.time.Instant
import java.time.LocalDate

/**
 * A row of the automation journal (AUT-1, §11.4): a day header, a batch (one pass of a rule, one AI fill, one
 * integration update), or the day's AI fills folded into one row — a separate category, collapsed by default.
 */
@Immutable
sealed interface JournalRow {
    val key: String
    val contentType: String

    data class Day(val day: LocalDate) : JournalRow {
        override val key: String get() = "day-$day"
        override val contentType: String get() = "day"
    }

    data class Batch(val batch: JournalBatch) : JournalRow {
        override val key: String get() = "batch-${batch.batchId}"
        override val contentType: String get() = "batch"
    }

    data class AiGroup(val day: LocalDate, val batches: List<JournalBatch>, val expanded: Boolean) : JournalRow {
        override val key: String get() = "ai-$day"
        override val contentType: String get() = "ai"

        /** Distinct tasks the AI filled in during the day. */
        val taskCount: Int get() = batches.flatMap { it.entries }.map { it.event.entityId }.distinct().size
    }
}

/** Undo of a batch or an entry: available within 30 days, already done, or no longer possible (AUT-1, R9). */
enum class UndoState { AVAILABLE, PARTLY_UNDONE, UNDONE, EXPIRED }

/** How many distinct tasks and projects a batch changed. */
data class EntityCounts(val tasks: Int, val projects: Int)

/** What to tell after an undo (interpretation 16): nothing was left to undo, some fields were kept, or done. */
enum class UndoReport { NOTHING_TO_UNDO, CONFLICTS, UNDONE }

object JournalRows {
    /**
     * Rows from the batches of [app.tasker.core.data.repository.JournalRepository]: newest day first, inside a day
     * rule and integration batches newest first, then the AI group; its batches follow it when the day is in
     * [expandedAiDays].
     */
    fun build(batches: List<JournalBatch>, expandedAiDays: Set<LocalDate>): List<JournalRow> = buildList {
        batches.sortedByDescending { it.createdAt }.groupBy { it.day }.forEach { (day, list) ->
            add(JournalRow.Day(day))
            val (ai, other) = list.partition { it.isAi }
            other.forEach { add(JournalRow.Batch(it)) }
            if (ai.isNotEmpty()) {
                val expanded = day in expandedAiDays
                add(JournalRow.AiGroup(day, ai, expanded))
                if (expanded) ai.forEach { add(JournalRow.Batch(it)) }
            }
        }
    }

    fun undoState(batch: JournalBatch, now: Instant): UndoState = when {
        batch.undone -> UndoState.UNDONE
        batch.canUndo(now) -> if (batch.entries.any { it.event.isUndone }) UndoState.PARTLY_UNDONE else UndoState.AVAILABLE
        else -> UndoState.EXPIRED
    }

    fun undoState(entry: JournalEntry, now: Instant): UndoState = when {
        entry.event.isUndone -> UndoState.UNDONE
        entry.canUndo(now) -> UndoState.AVAILABLE
        else -> UndoState.EXPIRED
    }

    fun counts(batch: JournalBatch): EntityCounts {
        fun count(type: EntityType) = batch.entries.filter { it.event.entityType == type }.map { it.event.entityId }.distinct().size
        return EntityCounts(tasks = count(EntityType.TASK), projects = count(EntityType.PROJECT))
    }

    fun report(outcome: UndoOutcome): UndoReport = when {
        outcome.nothingToUndo -> UndoReport.NOTHING_TO_UNDO
        outcome.conflicts.isNotEmpty() -> UndoReport.CONFLICTS
        else -> UndoReport.UNDONE
    }
}
