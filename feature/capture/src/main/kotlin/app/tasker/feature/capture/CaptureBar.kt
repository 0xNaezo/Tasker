package app.tasker.feature.capture

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.model.Bucket
import app.tasker.core.model.CaptureChannel
import app.tasker.core.model.Deadline
import app.tasker.core.model.Estimate
import app.tasker.core.model.ProjectId
import app.tasker.core.model.Task
import app.tasker.core.parser.ParseResult
import app.tasker.core.parser.ParsedField
import app.tasker.core.ui.text.UiText
import java.time.LocalDate

/**
 * The input line (CAP-1): pinned above the bottom navigation on the four main tabs and used alone by quick capture.
 * Chips update while typing; Enter saves without confirmation (§9.5).
 *
 * @param announce post the result to the app snackbar with Undo; quick capture shows its own confirmation instead.
 */
@Composable
fun CaptureBar(
    modifier: Modifier = Modifier,
    channel: CaptureChannel = CaptureChannel.BAR,
    announce: Boolean = true,
    autoFocus: Boolean = false,
    startVoice: Boolean = false,
    onSaved: (Task, UiText) -> Unit = { _, _ -> },
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var field by remember { mutableStateOf(TextFieldValue()) }
    // A failed save gives the text back.
    val restored by viewModel.restored.collectAsStateWithLifecycle()
    LaunchedEffect(restored) {
        restored?.let { text ->
            field = TextFieldValue(text, TextRange(text.length))
            viewModel.onRestored()
        }
    }
    val voice = rememberVoiceInput { spoken ->
        val text = if (field.text.isBlank()) spoken else field.text.trimEnd() + " " + spoken
        field = TextFieldValue(text, TextRange(text.length))
        viewModel.onTextChange(text)
    }
    var voiceStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startVoice, voice) {
        if (startVoice && !voiceStarted && voice != null) {
            voiceStarted = true
            voice.start()
        }
    }
    val actions = remember(viewModel) { ViewModelChipActions(viewModel) }
    CaptureBarContent(
        field = field,
        parse = state.parse,
        draft = state.draft,
        projects = state.projects,
        actions = actions,
        voice = voice,
        autoFocus = autoFocus,
        modifier = modifier,
        onValueChange = { value ->
            field = value
            viewModel.onTextChange(value.text)
        },
        onSubmit = {
            if (field.text.isNotBlank()) {
                viewModel.onTextChange(field.text)
                viewModel.submit(channel, announce, onSaved)
                field = TextFieldValue()
            }
        },
    )
}

@Composable
private fun CaptureBarContent(
    field: TextFieldValue,
    parse: ParseResult?,
    draft: CaptureDraft,
    projects: List<app.tasker.core.model.Project>,
    actions: ChipActions,
    voice: VoiceInput?,
    autoFocus: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    val highlight = MaterialTheme.colorScheme.primary
    val transformation = remember(parse, field.text, highlight) {
        if (parse == null || parse.input != field.text) VisualTransformation.None else FieldHighlight(parse.fields, highlight)
    }
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(Modifier.padding(vertical = TaskerTheme.spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = field,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus),
                    placeholder = { Text(stringResource(R.string.capture_placeholder)) },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                if (field.text.isBlank() && voice != null) {
                    IconButton(onClick = { voice.start() }) {
                        Icon(Icons.Outlined.Mic, contentDescription = stringResource(R.string.capture_voice))
                    }
                } else {
                    IconButton(onClick = onSubmit, enabled = field.text.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.capture_save))
                    }
                }
            }
            CaptureChips(
                parse = parse?.takeIf { it.input == field.text },
                draft = draft,
                projects = projects,
                actions = actions,
                modifier = Modifier.padding(bottom = TaskerTheme.spacing.xs),
            )
        }
    }
}

/** Colours the recognized fragments in the input so the user sees what became a chip. */
private class FieldHighlight(fields: List<ParsedField>, color: Color) : VisualTransformation {
    private val ranges = fields.flatMap { it.ranges }
    private val style = SpanStyle(color = color, textDecoration = TextDecoration.Underline)

    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text)
        ranges.filter { it.first >= 0 && it.last < text.length }.forEach { builder.addStyle(style, it.first, it.last + 1) }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean = other is FieldHighlight && other.ranges == ranges && other.style == style

    override fun hashCode(): Int = 31 * ranges.hashCode() + style.hashCode()
}

private class ViewModelChipActions(private val viewModel: CaptureViewModel) : ChipActions {
    override fun returnToText(field: ParsedField) = viewModel.returnToText(field)

    override fun setDeadline(value: Deadline?) = viewModel.setDeadline(value)

    override fun setPlanDate(value: LocalDate?) = viewModel.setPlanDate(value)

    override fun setEstimate(value: Estimate?) = viewModel.setEstimate(value)

    override fun setBucket(value: Bucket?) = viewModel.setBucket(value)

    override fun setProject(value: ProjectId?) = viewModel.setProject(value)

    override fun clearOverride(kind: OverrideKind) = viewModel.clearOverride(kind)
}
