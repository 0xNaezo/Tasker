package app.tasker.feature.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import app.tasker.core.data.command.TaskCommands
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.ProjectId
import app.tasker.core.model.Task
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.action.TaskActions
import app.tasker.core.ui.message.Messenger
import app.tasker.core.ui.text.UiText
import app.tasker.core.ui.util.attempt
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An archived task and the logical day it went to the archive. */
@Immutable
data class ArchivedItem(val task: Task, val archivedOn: LocalDate?)

@Immutable
data class ArchiveUiState(
    val count: Int = 0,
    /** All projects: archived tasks often belong to archived projects, which restoring brings back too. */
    val projectNames: Map<ProjectId, String> = emptyMap(),
    /** The task waiting for the "Delete forever" confirmation (ARC-1). */
    val pendingDelete: Task? = null,
)

/**
 * "Archive" (ARC-1, TTL-8, tech plan §15): archived tasks by archive date, kept without a time limit. Restore is one tap
 * with Undo; permanent deletion — the only physical deletion in the app — happens only after a confirmation.
 */
@HiltViewModel
class ArchiveViewModel @Inject constructor(
    tasks: TaskRepository,
    clock: DayClock,
    private val commands: TaskCommands,
    private val actions: TaskActions,
    private val messenger: Messenger,
) : ViewModel() {
    private val pendingDelete = MutableStateFlow<Task?>(null)

    val archive: Flow<PagingData<ArchivedItem>> = tasks.archive()
        .map { page -> page.map { task -> ArchivedItem(task, task.archivedAt?.let(clock::logicalDay)) } }
        .cachedIn(viewModelScope)

    val state: StateFlow<ArchiveUiState> = combine(
        tasks.observeArchiveCount(),
        tasks.observeProjects(),
        pendingDelete,
    ) { count, projects, pending ->
        ArchiveUiState(count, projects.associate { it.project.id to it.project.name }, pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ArchiveUiState())

    /** One tap (TTL-8): Open again, in its bucket or project; the snackbar offers Undo. */
    fun restore(task: Task) {
        viewModelScope.launch { actions.restore(task) }
    }

    fun askDelete(task: Task) {
        pendingDelete.value = task
    }

    fun cancelDelete() {
        pendingDelete.value = null
    }

    /** Deletes the confirmed task with its snapshots, sources, index and history; there is no undo. */
    fun confirmDelete() {
        val task = pendingDelete.value ?: return
        pendingDelete.value = null
        viewModelScope.launch {
            attempt { commands.deletePermanently(task.id) }
                .onSuccess { messenger.info(UiText.Res(R.string.archive_deleted, task.title)) }
                .onFailure { messenger.info(UiText.Res(UiR.string.error_generic)) }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
