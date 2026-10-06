package app.tasker.feature.capture

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tasker.core.data.command.CaptureOverrides
import app.tasker.core.data.command.CaptureRequest
import app.tasker.core.data.command.CaptureService
import app.tasker.core.data.command.FieldUpdate
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.model.Bucket
import app.tasker.core.model.CaptureChannel
import app.tasker.core.model.Deadline
import app.tasker.core.model.Estimate
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectId
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import app.tasker.core.parser.ParseResult
import app.tasker.core.parser.ParsedField
import app.tasker.core.parser.ParsedValue
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.message.Messenger
import app.tasker.core.ui.text.UiText
import app.tasker.core.ui.util.attempt
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The text being typed and the user's corrections of its chips (CAP-4): fragments returned to plain text and values
 * picked by hand.
 */
@Immutable
data class CaptureDraft(
    val text: String = "",
    val literalRanges: List<IntRange> = emptyList(),
    val overrides: CaptureOverrides = CaptureOverrides(),
) {
    /** Keeps literal ranges in place while the text is edited; a range touched by the edit is dropped. */
    fun withText(new: String): CaptureDraft {
        if (new == text) return this
        if (new.isBlank()) return CaptureDraft(text = new)
        val prefix = text.commonPrefixWith(new).length
        val suffix = text.substring(prefix).commonSuffixWith(new.substring(prefix)).length
        val oldEnd = text.length - suffix
        val delta = new.length - text.length
        val ranges = literalRanges.mapNotNull { range ->
            when {
                range.last < prefix -> range
                range.first >= oldEnd -> (range.first + delta)..(range.last + delta)
                else -> null
            }
        }
        return copy(text = new, literalRanges = ranges)
    }
}

@Immutable
data class CaptureUiState(
    val draft: CaptureDraft = CaptureDraft(),
    /** Parse of [CaptureDraft.text]; may lag one keystroke behind the field. */
    val parse: ParseResult? = null,
    val projects: List<Project> = emptyList(),
)

/** A field the user can set by hand from a chip. */
enum class OverrideKind { DEADLINE, PLAN_DATE, ESTIMATE, BUCKET, PROJECT }

/** Capture line (CAP-1…CAP-5, CAP-8): local parse while typing, Enter saves what the chips show. */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val capture: CaptureService,
    private val messenger: Messenger,
    settings: SettingsRepository,
    tasks: TaskRepository,
) : ViewModel() {
    private val draft = MutableStateFlow(CaptureDraft())
    private val restoredText = MutableStateFlow<String?>(null)

    /** Text to put back into the field after a failed save. */
    val restored: StateFlow<String?> = restoredText.asStateFlow()

    val state: StateFlow<CaptureUiState> = combine(draft, settings.settings, tasks.observeActiveProjects()) { draft, s, projects ->
        val parse = if (draft.text.isBlank()) {
            null
        } else {
            capture.parse(draft.text, s, projects.mapTo(LinkedHashSet()) { it.name }, draft.literalRanges)
        }
        CaptureUiState(draft, parse, projects)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CaptureUiState())

    fun onTextChange(text: String) = draft.update { it.withText(text) }

    fun onRestored() {
        restoredText.value = null
    }

    /** "×" on a chip: the fragment goes back to the text and the parser treats it as a literal (§9.5). */
    fun returnToText(field: ParsedField) = draft.update { current ->
        current.copy(
            literalRanges = (current.literalRanges + field.ranges).distinct(),
            overrides = current.overrides.clear(field.value.overrideKind()),
        )
    }

    fun setDeadline(value: Deadline?) = draft.update { it.copy(overrides = it.overrides.copy(deadline = FieldUpdate.Set(value))) }

    fun setPlanDate(value: LocalDate?) = draft.update { it.copy(overrides = it.overrides.copy(planDate = FieldUpdate.Set(value))) }

    fun setEstimate(value: Estimate?) = draft.update { it.copy(overrides = it.overrides.copy(estimate = FieldUpdate.Set(value))) }

    fun setBucket(value: Bucket?) = draft.update { it.copy(overrides = it.overrides.copy(bucket = FieldUpdate.Set(value))) }

    fun setProject(value: ProjectId?) = draft.update { it.copy(overrides = it.overrides.copy(projectId = FieldUpdate.Set(value))) }

    fun clearOverride(kind: OverrideKind) = draft.update { it.copy(overrides = it.overrides.clear(kind)) }

    /**
     * Enter: saves at once without confirmation; the line is cleared right away and stays focused (§9.5). The capture
     * bar announces the result in the app snackbar with Undo; quick capture shows its own confirmation.
     */
    fun submit(channel: CaptureChannel = CaptureChannel.BAR, announce: Boolean = true, onSaved: (Task, UiText) -> Unit = { _, _ -> }) {
        val current = draft.value
        if (current.text.isBlank()) return
        draft.value = CaptureDraft()
        viewModelScope.launch {
            attempt { capture.capture(CaptureRequest(current.text, channel, current.literalRanges, current.overrides)) }
                .onSuccess { result ->
                    val outcome = result.value
                    val projectName = outcome.createdProject?.name
                        ?: outcome.task.projectId?.let { id -> state.value.projects.firstOrNull { it.id == id }?.name }
                    val text = savedText(outcome.task, projectName)
                    if (announce) messenger.undoable(text, result.batchId)
                    onSaved(outcome.task, text)
                }
                .onFailure {
                    draft.value = current
                    restoredText.value = current.text
                    messenger.info(UiText.Res(R.string.capture_failed))
                }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** "Saved to Inbox", "Saved to Today", "Saved to project X", "Added to the done log". */
internal fun savedText(task: Task, projectName: String?): UiText = when {
    task.status == TaskStatus.DONE -> UiText.Res(R.string.capture_saved_done, task.title)
    projectName != null -> UiText.Res(R.string.capture_saved_project, projectName)
    else -> UiText.Res(
        R.string.capture_saved_to,
        UiText.Res(
            when (task.bucket) {
                Bucket.TODAY -> UiR.string.bucket_today
                Bucket.WEEK -> UiR.string.bucket_week
                Bucket.SOMEDAY -> UiR.string.bucket_someday
                null -> UiR.string.bucket_inbox
            },
        ),
    )
}

internal fun ParsedValue.overrideKind(): OverrideKind? = when (this) {
    is ParsedValue.Deadline -> OverrideKind.DEADLINE
    is ParsedValue.PlanDate -> OverrideKind.PLAN_DATE
    is ParsedValue.Size -> OverrideKind.ESTIMATE
    is ParsedValue.Horizon -> OverrideKind.BUCKET
    is ParsedValue.Project -> OverrideKind.PROJECT
    is ParsedValue.Tag, ParsedValue.AlreadyDone -> null
}

private fun CaptureOverrides.clear(kind: OverrideKind?): CaptureOverrides = when (kind) {
    OverrideKind.DEADLINE -> copy(deadline = FieldUpdate.Keep)
    OverrideKind.PLAN_DATE -> copy(planDate = FieldUpdate.Keep)
    OverrideKind.ESTIMATE -> copy(estimate = FieldUpdate.Keep)
    OverrideKind.BUCKET -> copy(bucket = FieldUpdate.Keep)
    OverrideKind.PROJECT -> copy(projectId = FieldUpdate.Keep)
    null -> this
}
