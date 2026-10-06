package app.tasker.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tasker.core.data.command.ProjectArchivePreview
import app.tasker.core.data.repository.ProjectDetail
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.model.Bucket
import app.tasker.core.model.ProjectId
import app.tasker.core.model.ProjectStatus
import app.tasker.core.model.Task
import app.tasker.core.model.TaskId
import app.tasker.core.model.TaskStatus
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.component.Banner
import app.tasker.core.ui.component.EmptyState
import app.tasker.core.ui.component.RowAction
import app.tasker.core.ui.component.SectionHeader
import app.tasker.core.ui.component.TaskRow

/** Project card (EXE-4, EXE-5): outcome, steps with "add step", completion, archive and restore. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    projectId: ProjectId,
    onBack: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProjectViewModel = hiltViewModel<ProjectViewModel, ProjectViewModel.Factory>(
        key = projectId,
        creationCallback = { it.create(projectId) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preview by viewModel.archivePreview.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<EditField?>(null) }
    var restoring by rememberSaveable { mutableStateOf(false) }
    val detail = state.detail
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(detail?.project?.name.orEmpty(), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.tasks_back))
                    }
                },
                actions = {
                    if (detail != null) {
                        ProjectMenu(
                            status = detail.project.status,
                            onRename = { editing = EditField.NAME },
                            onOutcome = { editing = EditField.OUTCOME },
                            onComplete = viewModel::complete,
                            onReopen = viewModel::reopen,
                            onArchive = viewModel::requestArchive,
                            onRestore = { restoring = true },
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (detail?.project?.status == ProjectStatus.ACTIVE) AddStepBar(onAdd = viewModel::addStep)
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            detail == null -> EmptyState(title = stringResource(R.string.tasks_project_missing), modifier = Modifier.padding(padding))
            else -> ProjectContent(detail, viewModel, onOpenTask, onEditOutcome = {
                editing = EditField.OUTCOME
            }, Modifier.padding(padding))
        }
    }
    val project = detail?.project
    if (project != null) {
        when (editing) {
            EditField.NAME -> TextDialog(
                title = stringResource(R.string.tasks_rename),
                initial = project.name,
                allowEmpty = false,
                onDismiss = { editing = null },
                onSave = {
                    editing = null
                    viewModel.rename(it)
                },
            )
            EditField.OUTCOME -> TextDialog(
                title = stringResource(R.string.tasks_outcome),
                initial = project.outcome.orEmpty(),
                allowEmpty = true,
                onDismiss = { editing = null },
                onSave = {
                    editing = null
                    viewModel.setOutcome(it.ifBlank { null })
                },
            )
            null -> Unit
        }
    }
    preview?.let { ArchiveDialog(it, onConfirm = viewModel::confirmArchive, onDismiss = viewModel::cancelArchive) }
    if (restoring) {
        AlertDialog(
            onDismissRequest = { restoring = false },
            title = { Text(stringResource(R.string.tasks_restore_title)) },
            text = { Text(stringResource(R.string.tasks_restore_body)) },
            confirmButton = {
                TextButton(onClick = {
                    restoring = false
                    viewModel.restore(withTasks = true)
                }) { Text(stringResource(R.string.tasks_restore_with_tasks)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    restoring = false
                    viewModel.restore(withTasks = false)
                }) { Text(stringResource(R.string.tasks_restore_only_project)) }
            },
        )
    }
}

private enum class EditField { NAME, OUTCOME }

@Composable
private fun ProjectContent(
    detail: ProjectDetail,
    viewModel: ProjectViewModel,
    onOpenTask: (TaskId) -> Unit,
    onEditOutcome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = detail.tasks.filter { it.status.isActive }.sortedBy { it.position }
    val done = detail.tasks.filter { it.status == TaskStatus.DONE }.sortedByDescending { it.completedAt }
    var showDone by rememberSaveable { mutableStateOf(false) }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item(key = "outcome") {
            Card(
                onClick = onEditOutcome,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(TaskerTheme.spacing.l),
            ) {
                Column(Modifier.padding(TaskerTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.xs)) {
                    Text(stringResource(R.string.tasks_outcome), style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = detail.project.outcome ?: stringResource(R.string.tasks_outcome_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (detail.project.outcome ==
                            null
                        ) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
        if (detail.noNextStep) {
            item(key = "no-step") {
                Banner(
                    text = stringResource(R.string.tasks_no_next_step),
                    modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l),
                )
            }
        }
        if (active.isNotEmpty()) {
            item(key = "active-header") { SectionHeader(pluralStringResource(R.plurals.tasks_project_count, active.size, active.size)) }
        }
        items(active, key = { it.id }) { task ->
            TaskRow(
                task = task,
                onClick = { onOpenTask(task.id) },
                onComplete = { viewModel.toggleDone(task) },
                onPostpone = { viewModel.postpone(task, it) },
                actions = projectTaskActions(task, viewModel),
            )
        }
        if (done.isNotEmpty()) {
            item(key = "done-header") {
                SectionHeader(
                    title = stringResource(R.string.tasks_project_done, done.size),
                    modifier = Modifier.clickable { showDone = !showDone },
                )
            }
            if (showDone) {
                items(done, key = { "done-${it.id}" }) { task ->
                    TaskRow(
                        task = task,
                        onClick = { onOpenTask(task.id) },
                        onComplete = { viewModel.toggleDone(task) },
                        onPostpone = { viewModel.postpone(task, it) },
                        swipeEnabled = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun projectTaskActions(task: Task, viewModel: ProjectViewModel): List<RowAction> = listOf(
    RowAction(stringResource(UiR.string.action_add_to_plan), Icons.Outlined.PlaylistAdd) { viewModel.addToPlan(task) },
    RowAction(stringResource(UiR.string.action_to_today), Icons.Outlined.WbSunny) { viewModel.move(task, Bucket.TODAY) },
    RowAction(stringResource(UiR.string.action_to_week), Icons.Outlined.DateRange) { viewModel.move(task, Bucket.WEEK) },
    RowAction(stringResource(UiR.string.action_to_someday), Icons.Outlined.NightsStay) { viewModel.move(task, Bucket.SOMEDAY) },
    RowAction(stringResource(R.string.tasks_remove_from_project), Icons.Outlined.FolderOff) { viewModel.removeFromProject(task) },
    RowAction(stringResource(UiR.string.action_archive), Icons.Outlined.Archive) { viewModel.archive(task) },
)

@Composable
private fun ProjectMenu(
    status: ProjectStatus,
    onRename: () -> Unit,
    onOutcome: () -> Unit,
    onComplete: () -> Unit,
    onReopen: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(UiR.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val close = { open = false }
            MenuItem(R.string.tasks_rename, close, onRename)
            MenuItem(R.string.tasks_outcome, close, onOutcome)
            when (status) {
                ProjectStatus.ACTIVE -> {
                    MenuItem(R.string.tasks_complete_project, close, onComplete)
                    MenuItem(R.string.tasks_archive_project, close, onArchive)
                }
                ProjectStatus.COMPLETED -> {
                    MenuItem(R.string.tasks_reopen_project, close, onReopen)
                    MenuItem(R.string.tasks_archive_project, close, onArchive)
                }
                ProjectStatus.ARCHIVED -> MenuItem(R.string.tasks_restore_project, close, onRestore)
            }
        }
    }
}

@Composable
private fun MenuItem(text: Int, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(text)) },
        onClick = {
            close()
            action()
        },
    )
}

@Composable
private fun AddStepBar(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        onAdd(text)
        text = ""
    }
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = TaskerTheme.spacing.s, vertical = TaskerTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.tasks_add_step)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            IconButton(onClick = submit, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.tasks_add_step))
            }
        }
    }
}

@Composable
private fun TextDialog(title: String, initial: String, allowEmpty: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = allowEmpty || text.isNotBlank()) {
                Text(stringResource(R.string.tasks_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) } },
    )
}

@Composable
private fun ArchiveDialog(preview: ProjectArchivePreview, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tasks_archive_project)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s)) {
                Text(pluralStringResource(R.plurals.tasks_archive_tasks, preview.toArchive.size, preview.toArchive.size))
                if (preview.toDetach.isNotEmpty()) {
                    Text(pluralStringResource(R.plurals.tasks_archive_detach, preview.toDetach.size, preview.toDetach.size))
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(UiR.string.action_archive)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) } },
    )
}
