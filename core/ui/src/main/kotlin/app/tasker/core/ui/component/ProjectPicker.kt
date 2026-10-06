package app.tasker.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tasker.core.model.Project
import app.tasker.core.model.ProjectId
import app.tasker.core.ui.R

/**
 * Choose a project for a task, take it out of its project, or create a new one by name.
 *
 * @param onPick the chosen project, or `null` for "no project" (shown when [allowNone]).
 */
@Composable
fun ProjectPickerDialog(
    projects: List<Project>,
    current: ProjectId?,
    onDismiss: () -> Unit,
    onPick: (ProjectId?) -> Unit,
    onCreate: (name: String) -> Unit,
    allowNone: Boolean = true,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (creating) R.string.project_new else R.string.project_pick)) },
        text = {
            if (creating) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.project_name_hint)) },
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (allowNone) {
                        item {
                            PickerItem(
                                text = stringResource(R.string.project_none),
                                selected = current == null,
                                icon = { Icon(Icons.Outlined.FolderOff, contentDescription = null) },
                                onClick = { onPick(null) },
                            )
                        }
                    }
                    items(projects, key = { it.id }) { project ->
                        PickerItem(
                            text = project.name,
                            selected = project.id == current,
                            icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                            onClick = { onPick(project.id) },
                        )
                    }
                    item {
                        PickerItem(
                            text = stringResource(R.string.project_new),
                            selected = false,
                            icon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                            onClick = { creating = true },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) {
                    Text(stringResource(R.string.project_create))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PickerItem(text: String, selected: Boolean, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Column {
        ListItem(
            headlineContent = { Text(text, maxLines = 2) },
            leadingContent = icon,
            trailingContent = if (selected) {
                { Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.project_current)) }
            } else {
                null
            },
            modifier = Modifier.clickable(onClick = onClick),
        )
    }
}
