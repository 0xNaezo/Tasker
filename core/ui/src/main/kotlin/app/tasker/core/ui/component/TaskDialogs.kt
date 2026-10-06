package app.tasker.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.model.Task
import app.tasker.core.ui.R

/**
 * Pause with a context snapshot (EXC-3): "where did I stop, what's next". The note is optional — pausing never
 * requires typing.
 */
@Composable
fun PauseDialog(task: Task, onDismiss: () -> Unit, onPause: (note: String?) -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pause_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s)) {
                Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                    placeholder = { Text(stringResource(R.string.pause_hint)) },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPause(note.ifBlank { null }) }) { Text(stringResource(R.string.pause_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Soft WIP limit (DAT-7): the task has started anyway; the dialog offers to pause one of the others.
 */
@Composable
fun WipLimitDialog(
    started: Task,
    inProgress: List<Task>,
    limit: Int,
    onPause: (Task) -> Unit,
    onDismiss: () -> Unit,
) {
    val others = inProgress.filter { it.id != started.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.wip_title, inProgress.size, inProgress.size, limit)) },
        text = {
            Column {
                Text(stringResource(R.string.wip_body), style = MaterialTheme.typography.bodyMedium)
                others.forEach { task ->
                    ListItem(
                        headlineContent = { Text(task.title, maxLines = 2) },
                        trailingContent = { TextButton(onClick = { onPause(task) }) { Text(stringResource(R.string.wip_pause)) } },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.wip_keep_all)) } },
    )
}

/** Split into 2–5 steps (DAT-3, PLN-8): the task becomes a project, every line a step. */
@Composable
fun SplitDialog(task: Task, onDismiss: () -> Unit, onSplit: (List<String>) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val steps = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.split_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s)) {
                Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                    placeholder = { Text(stringResource(R.string.split_hint)) },
                    supportingText = { Text(stringResource(R.string.split_rules)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSplit(steps) }, enabled = steps.size in MIN_STEPS..MAX_STEPS) {
                Text(stringResource(R.string.split_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val MIN_STEPS = 2
private const val MAX_STEPS = 5
