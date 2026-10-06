package app.tasker.feature.task

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tasker.core.data.command.FieldUpdate
import app.tasker.core.data.command.SnapshotInput
import app.tasker.core.data.command.StartOutcome
import app.tasker.core.data.command.TaskCommands
import app.tasker.core.data.command.TaskEdit
import app.tasker.core.data.command.TxResult
import app.tasker.core.data.command.UndoService
import app.tasker.core.data.repository.TaskDetail
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.model.AppSettings
import app.tasker.core.model.Bucket
import app.tasker.core.model.Deadline
import app.tasker.core.model.Estimate
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectId
import app.tasker.core.model.ReminderOffsets
import app.tasker.core.model.TaskField
import app.tasker.core.model.TaskId
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.action.TaskActions
import app.tasker.core.ui.message.Messenger
import app.tasker.core.ui.text.UiText
import app.tasker.core.ui.util.attempt
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class TaskUiState(
    val loading: Boolean = true,
    val detail: TaskDetail? = null,
    val projects: List<Project> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

/** Task card (EXC-1…EXC-5, AI-4, DAT-1): every field editable, every action available as a button. */
@HiltViewModel(assistedFactory = TaskViewModel.Factory::class)
class TaskViewModel @AssistedInject constructor(
    @Assisted private val taskId: TaskId,
    tasks: TaskRepository,
    settings: SettingsRepository,
    private val commands: TaskCommands,
    private val undo: UndoService,
    private val actions: TaskActions,
    private val messenger: Messenger,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(taskId: TaskId): TaskViewModel
    }

    val state: StateFlow<TaskUiState> = combine(tasks.observeDetail(taskId), tasks.observeActiveProjects(), settings.settings) {
            detail,
            projects,
            s,
        ->
        TaskUiState(loading = false, detail = detail, projects = projects, settings = s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TaskUiState())

    private val overLimit = MutableStateFlow<StartOutcome?>(null)
    val wipOutcome: StateFlow<StartOutcome?> = overLimit.asStateFlow()

    private val task get() = state.value.detail?.task

    fun setTitle(title: String) {
        if (title.isBlank() || title == task?.title) return
        edit(TaskEdit(title = FieldUpdate.Set(title.trim())))
    }

    fun setNote(note: String) {
        if (note == task?.note.orEmpty()) return
        edit(TaskEdit(note = FieldUpdate.Set(note.trim().ifEmpty { null })))
    }

    fun setBucket(bucket: Bucket?) = edit(TaskEdit(bucket = FieldUpdate.Set(bucket)))

    fun setPlanDate(date: LocalDate?) = edit(TaskEdit(planDate = FieldUpdate.Set(date)))

    fun setDeadline(deadline: Deadline?) = edit(TaskEdit(deadline = FieldUpdate.Set(deadline)))

    fun setEstimate(estimate: Estimate?) = edit(TaskEdit(estimate = FieldUpdate.Set(estimate)))

    fun setProject(projectId: ProjectId?) = edit(TaskEdit(projectId = FieldUpdate.Set(projectId)))

    fun setTags(tags: List<String>) = edit(TaskEdit(tags = FieldUpdate.Set(tags)))

    fun setReminders(offsets: ReminderOffsets?) = command { commands.setReminders(taskId, offsets) }

    fun moveToNewProject(name: String) = withTask { actions.moveToNewProject(it, name) }

    fun toggleDone() = withTask { actions.toggleDone(it) }

    fun start() = withTask { task -> actions.start(task)?.takeIf { it.overLimit }?.let { overLimit.value = it } }

    fun pause(note: String?) = withTask { actions.pause(it, note) }

    fun pauseOther(other: app.tasker.core.model.Task) {
        overLimit.value = null
        viewModelScope.launch { actions.pause(other, null) }
    }

    fun dismissWip() {
        overLimit.value = null
    }

    fun postpone(option: PostponeOption) = withTask { actions.postpone(it, option) }

    fun addToPlan() = withTask { actions.addToPlan(it) }

    fun archive() = withTask { actions.archive(it) }

    fun restore() = withTask { actions.restore(it) }

    fun split(steps: List<String>) = withTask { actions.split(it, steps) }

    /** EXC-4: a context note without pausing. */
    fun addSnapshot(text: String) {
        if (text.isBlank()) return
        command { commands.addSnapshot(taskId, SnapshotInput(text)) }
    }

    /** AI-4: return AI-filled fields (all or one) to their previous values in one tap. */
    fun undoAi(field: TaskField? = null) {
        viewModelScope.launch {
            attempt { undo.undoAiFill(taskId, field?.let { setOf(it) }) }
                .onSuccess { result ->
                    val outcome = result.value
                    when {
                        outcome.nothingToUndo -> messenger.info(UiText.Res(UiR.string.undo_expired))
                        outcome.conflicts.isNotEmpty() -> messenger.info(UiText.Res(UiR.string.undo_conflicts))
                        else -> messenger.undoable(UiText.Res(R.string.task_ai_undone), result.batchId)
                    }
                }
                .onFailure { messenger.info(UiText.Res(UiR.string.error_generic)) }
        }
    }

    /** Permanent deletion: only from the archive and after confirmation (ARC-1). */
    fun deletePermanently(onDeleted: () -> Unit) {
        viewModelScope.launch {
            attempt { commands.deletePermanently(taskId) }
                .onSuccess {
                    messenger.info(UiText.Res(R.string.task_deleted))
                    onDeleted()
                }
                .onFailure { messenger.info(UiText.Res(UiR.string.error_generic)) }
        }
    }

    private fun edit(edit: TaskEdit) = command { commands.edit(taskId, edit) }

    private fun command(block: suspend () -> TxResult<*>) {
        viewModelScope.launch {
            attempt(block)
                .onSuccess { messenger.undoable(UiText.Res(UiR.string.undo_saved), it.batchId) }
                .onFailure { messenger.info(UiText.Res(UiR.string.error_generic)) }
        }
    }

    private fun withTask(block: suspend (app.tasker.core.model.Task) -> Unit) {
        val current = task ?: return
        viewModelScope.launch { block(current) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
