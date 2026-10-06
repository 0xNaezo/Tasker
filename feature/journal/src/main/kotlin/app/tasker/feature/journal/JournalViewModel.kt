package app.tasker.feature.journal

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tasker.core.data.command.TxResult
import app.tasker.core.data.command.UndoOutcome
import app.tasker.core.data.command.UndoService
import app.tasker.core.data.repository.JournalBatch
import app.tasker.core.data.repository.JournalEntry
import app.tasker.core.data.repository.JournalRepository
import app.tasker.core.model.BatchId
import app.tasker.core.model.EntityType
import app.tasker.core.model.TaskId
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.message.Messenger
import app.tasker.core.ui.text.UiText
import app.tasker.core.ui.util.attempt
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A task whose fields the user changed after the automatic action, so undo kept them (interpretation 16). */
@Immutable
data class KeptTask(val taskId: TaskId, val title: String)

@Immutable
data class JournalUiState(
    val loading: Boolean = true,
    val rows: List<JournalRow> = emptyList(),
    /** Batches shown entry by entry, each with its own Undo. */
    val expanded: Set<BatchId> = emptySet(),
    /** Batches with an undo in flight: their buttons wait. */
    val busy: Set<BatchId> = emptySet(),
    /** Tasks of the last undo that kept the user's later changes; the screen offers to open them. */
    val kept: List<KeptTask> = emptyList(),
)

/**
 * "Automation journal" (AUT-1, §11.4): batches of automatic actions newest first; undo of a whole batch or of one task
 * while the 30-day window lasts. The outcome goes to the app snackbar; fields the user changed since stay as they are.
 */
@HiltViewModel
class JournalViewModel @Inject constructor(
    journal: JournalRepository,
    private val undo: UndoService,
    private val messenger: Messenger,
) : ViewModel() {
    private val aiDays = MutableStateFlow<Set<LocalDate>>(emptySet())
    private val expanded = MutableStateFlow<Set<BatchId>>(emptySet())
    private val busy = MutableStateFlow<Set<BatchId>>(emptySet())
    private val kept = MutableStateFlow<List<KeptTask>>(emptyList())

    val state: StateFlow<JournalUiState> = combine(journal.observeJournal(), aiDays, expanded, busy, kept) {
            batches,
            days,
            open,
            running,
            keptTasks,
        ->
        JournalUiState(loading = false, rows = JournalRows.build(batches, days), expanded = open, busy = running, kept = keptTasks)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), JournalUiState())

    fun toggle(batch: JournalBatch) {
        expanded.update { if (batch.batchId in it) it - batch.batchId else it + batch.batchId }
    }

    /** Opens or folds the day's AI fills (§11.4: a separate category, collapsed by default). */
    fun toggleAi(day: LocalDate) {
        aiDays.update { if (day in it) it - day else it + day }
    }

    fun dismissKept() {
        kept.value = emptyList()
    }

    /** Undo the whole batch: every entry that is still within its window. */
    fun undoBatch(batch: JournalBatch) = launchUndo(batch, batch.entries) { undo.undoBatch(batch.batchId) }

    /** Undo one task or project of the batch. */
    fun undoEntry(batch: JournalBatch, entry: JournalEntry) = launchUndo(batch, listOf(entry)) {
        undo.undoEntity(batch.batchId, entry.event.entityId)
    }

    private fun launchUndo(batch: JournalBatch, entries: List<JournalEntry>, block: suspend () -> TxResult<UndoOutcome>) {
        val id = batch.batchId
        if (id in busy.value) return
        busy.update { it + id }
        viewModelScope.launch {
            try {
                attempt(block)
                    .onSuccess { result -> report(result.value, entries) }
                    .onFailure { messenger.info(UiText.Res(UiR.string.error_generic)) }
            } finally {
                busy.update { it - id }
            }
        }
    }

    private fun report(outcome: UndoOutcome, entries: List<JournalEntry>) {
        val titles = entries.associate { it.event.entityId to it.title }
        kept.value =
            outcome.conflicts.filter { it.entityType == EntityType.TASK }.map { KeptTask(it.entityId, titles[it.entityId].orEmpty()) }
        messenger.info(
            when (JournalRows.report(outcome)) {
                UndoReport.NOTHING_TO_UNDO -> UiText.Res(UiR.string.undo_expired)
                UndoReport.CONFLICTS -> UiText.Res(UiR.string.undo_conflicts)
                UndoReport.UNDONE -> UiText.Plural(R.plurals.journal_undone_count, outcome.undoneEntities, outcome.undoneEntities)
            },
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
