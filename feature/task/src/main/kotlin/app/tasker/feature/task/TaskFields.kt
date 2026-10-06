package app.tasker.feature.task

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.ui.R as UiR

/**
 * One editable field of the card. AI-filled values carry a mark that undoes the fill in one tap (AI-4).
 *
 * @param onClear shown as "×" when the field has a value.
 */
@Composable
internal fun FieldRow(
    icon: ImageVector,
    label: String,
    value: String?,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
    aiMarked: Boolean = false,
    onUndoAi: () -> Unit = {},
    onClear: (() -> Unit)? = null,
) {
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = {
            Text(
                text = value ?: placeholder,
                color = when {
                    value == null -> TaskerTheme.colors.muted
                    valueColor != Color.Unspecified -> valueColor
                    else -> Color.Unspecified
                },
            )
        },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (aiMarked) {
                    AssistChip(
                        onClick = onUndoAi,
                        label = { Text(stringResource(R.string.task_ai_undo_field)) },
                        leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = TaskerTheme.colors.ai) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = TaskerTheme.colors.aiContainer),
                    )
                }
                if (value != null && onClear != null) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.task_clear_field, label))
                    }
                }
            }
        },
        modifier = modifier.clickable(onClickLabel = stringResource(R.string.task_edit_field, label), onClick = onClick),
    )
}

/** Single choice from a short list (horizon, size). */
@Composable
internal fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (label, value) ->
                    ListItem(
                        headlineContent = { Text(label) },
                        leadingContent = { RadioButton(selected = value == selected, onClick = null) },
                        modifier = Modifier.selectable(selected = value == selected, role = Role.RadioButton) { onPick(value) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) } },
    )
}

/** Free text for tags: separated by commas or spaces, `@` optional. */
@Composable
internal fun TagsDialog(initial: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial.joinToString(" ") { "@$it" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_field_tags)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.task_tags_hint)) },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(parseTags(text)) }) { Text(stringResource(R.string.task_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) } },
    )
}

internal fun parseTags(text: String): List<String> = text.split(',', ' ', '\n')
    .map { it.trim().removePrefix("@").trim() }
    .filter { it.isNotEmpty() }
    .distinctBy { it.lowercase() }

/** Deadline reminders (NTF-2): several offsets, or the app default. */
@Composable
internal fun RemindersDialog(
    initial: List<Int>?,
    defaults: List<Int>,
    label: @Composable (Int) -> String,
    onDismiss: () -> Unit,
    onSave: (List<Int>?) -> Unit,
) {
    var useDefault by rememberSaveable { mutableStateOf(initial == null) }
    var chosen by rememberSaveable { mutableStateOf((initial ?: defaults).toSet().toList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_field_reminders)) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.task_reminders_default)) },
                    leadingContent = { Checkbox(checked = useDefault, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = useDefault, role = Role.Checkbox) { useDefault = it },
                )
                REMINDER_PRESETS.forEach { minutes ->
                    val checked = minutes in chosen
                    ListItem(
                        headlineContent = { Text(label(minutes)) },
                        leadingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = !useDefault) },
                        modifier = Modifier.toggleable(value = checked, enabled = !useDefault, role = Role.Checkbox) { on ->
                            chosen = if (on) (chosen + minutes).sortedDescending() else chosen - minutes
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(if (useDefault) null else chosen.sortedDescending())
            }) { Text(stringResource(R.string.task_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) } },
    )
}

/** Offsets offered for reminders, minutes before the deadline; 0 is "at the deadline". */
internal val REMINDER_PRESETS = listOf(24 * 60, 2 * 60, 60, 30, 0)
