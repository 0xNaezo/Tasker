package app.tasker.feature.capture

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tasker.core.data.command.CaptureOutcome
import app.tasker.core.data.command.CaptureRequest
import app.tasker.core.data.command.CaptureService
import app.tasker.core.data.command.SharedOrigin
import app.tasker.core.data.command.UndoService
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.model.BatchId
import app.tasker.core.model.CaptureChannel
import app.tasker.core.ui.util.attempt
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Text received through "Share": a short line is parsed as is; a long text keeps its first line and goes to the note.
 * The title and the note have upper limits, so sharing a whole book does not make a task of it.
 */
internal data class SharedText(val input: String, val note: String?, val appPackage: String?, val url: String?) {
    companion object {
        const val MAX_TITLE = 200

        /** About ten pages of text. */
        const val MAX_NOTE = 20_000
        private val URL = Regex("""https?://\S+""")

        fun from(intent: Intent, appPackage: String?): SharedText? {
            if (intent.action != Intent.ACTION_SEND) return null
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty().limitedTo(MAX_TITLE)
            if (text.isEmpty() && subject.isEmpty()) return null
            val url = URL.find(text)?.value?.trimEnd('.', ',', ')', ']')
            val short = text.length <= MAX_TITLE && '\n' !in text
            return if (short) {
                val input = when {
                    subject.isEmpty() -> text
                    text.isEmpty() || text.contains(subject) -> text.ifEmpty { subject }
                    else -> "$subject $text"
                }
                SharedText(input, note = null, appPackage = appPackage, url = url)
            } else {
                val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().limitedTo(MAX_TITLE)
                SharedText(subject.ifEmpty { firstLine }, note = text.limitedTo(MAX_NOTE), appPackage = appPackage, url = url)
            }
        }

        /** At most [max] characters: a cut text ends with "…" and never splits a surrogate pair. */
        private fun String.limitedTo(max: Int): String {
            if (length <= max) return this
            var end = max - 1
            if (Character.isHighSurrogate(this[end - 1])) end--
            return substring(0, end).trimEnd() + "…"
        }
    }
}

internal sealed interface ShareState {
    data object Saving : ShareState

    data class Saved(val outcome: CaptureOutcome, val batchId: BatchId?, val projectName: String?) : ShareState

    data object Undone : ShareState

    data object Failed : ShareState
}

/** "Share" capture (CAP-6, scenario 1): saves at once; the sheet offers Undo and Edit. */
@HiltViewModel
internal class ShareViewModel @Inject constructor(
    private val capture: CaptureService,
    private val undo: UndoService,
    private val tasks: TaskRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ShareState>(ShareState.Saving)
    val state: StateFlow<ShareState> = mutableState.asStateFlow()

    fun save(shared: SharedText) {
        viewModelScope.launch {
            attempt {
                capture.capture(
                    CaptureRequest(
                        input = shared.input,
                        channel = CaptureChannel.SHARE,
                        note = shared.note,
                        sharedFrom = SharedOrigin(shared.appPackage, shared.url),
                    ),
                )
            }.onSuccess { result ->
                val outcome = result.value
                val projectName = outcome.createdProject?.name ?: outcome.task.projectId?.let { tasks.project(it)?.name }
                mutableState.value = ShareState.Saved(outcome, result.batchId, projectName)
            }
                .onFailure { mutableState.value = ShareState.Failed }
        }
    }

    fun undo() {
        val saved = mutableState.value as? ShareState.Saved ?: return
        viewModelScope.launch {
            saved.batchId?.let { batch -> attempt { undo.undoBatch(batch) } }
            mutableState.value = ShareState.Undone
        }
    }
}
