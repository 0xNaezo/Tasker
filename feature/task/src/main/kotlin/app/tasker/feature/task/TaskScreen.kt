package app.tasker.feature.task

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AppShortcut
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tasker.core.data.repository.TaskDetail
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.domain.plan.TaskTiming
import app.tasker.core.model.Actor
import app.tasker.core.model.Bucket
import app.tasker.core.model.ContextSnapshot
import app.tasker.core.model.Deadline
import app.tasker.core.model.Estimate
import app.tasker.core.model.Event
import app.tasker.core.model.ProjectId
import app.tasker.core.model.ReminderOffsets
import app.tasker.core.model.Source
import app.tasker.core.model.SourceKind
import app.tasker.core.model.Task
import app.tasker.core.model.TaskField
import app.tasker.core.model.TaskId
import app.tasker.core.model.TaskStatus
import app.tasker.core.ui.LocalDayContext
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.component.ConfirmDialog
import app.tasker.core.ui.component.DateDialog
import app.tasker.core.ui.component.EmptyState
import app.tasker.core.ui.component.PauseDialog
import app.tasker.core.ui.component.PostponeSheet
import app.tasker.core.ui.component.ProjectPickerDialog
import app.tasker.core.ui.component.ReasonLabel
import app.tasker.core.ui.component.SectionHeader
import app.tasker.core.ui.component.SplitDialog
import app.tasker.core.ui.component.TimeDialog
import app.tasker.core.ui.component.WipLimitDialog
import app.tasker.core.ui.format.Formats
import app.tasker.core.ui.format.Reasons
import java.time.LocalDate
import java.time.ZonedDateTime

private enum class Editor { BUCKET, PLAN_DATE, DEADLINE_DATE, DEADLINE_TIME, ESTIMATE, PROJECT, TAGS, REMINDERS }

private enum class Prompt { PAUSE, POSTPONE, SPLIT, SNAPSHOT, DELETE }

/**
 * Task card (§14.2): the latest context snapshot first (EXC-4), all actions as buttons (accessibility NFR), every
 * field editable with AI marks (AI-4), links and sources (EXC-5), and the history with reasons (principle 7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(
    taskId: TaskId,
    onBack: () -> Unit,
    onOpenProject: (ProjectId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TaskViewModel = hiltViewModel<TaskViewModel, TaskViewModel.Factory>(
        key = taskId,
        creationCallback = { it.create(taskId) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val wip by viewModel.wipOutcome.collectAsStateWithLifecycle()
    val detail = state.detail
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                // An open task needs no status in the title; other states (in progress, paused, done…) are news.
                title = {
                    val status = detail?.task?.status
                    Text(
                        if (status == null ||
                            status == TaskStatus.OPEN
                        ) {
                            stringResource(R.string.task_screen_title)
                        } else {
                            Formats.status(status)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.task_back))
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            detail == null -> EmptyState(title = stringResource(R.string.task_missing), modifier = Modifier.padding(padding))
            else -> TaskContent(detail, state, viewModel, onBack, onOpenProject, Modifier.padding(padding))
        }
    }
    wip?.let { WipLimitDialog(it.task, it.inProgress, it.limit, onPause = viewModel::pauseOther, onDismiss = viewModel::dismissWip) }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun TaskContent(
    detail: TaskDetail,
    state: TaskUiState,
    viewModel: TaskViewModel,
    onBack: () -> Unit,
    onOpenProject: (ProjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val task = detail.task
    var editor by rememberSaveable { mutableStateOf<Editor?>(null) }
    var prompt by rememberSaveable { mutableStateOf<Prompt?>(null) }
    var pendingDeadlineDate by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    val context = LocalDayContext.current
    val ai = detail.aiFields

    LazyColumn(modifier = modifier.fillMaxSize()) {
        detail.latestSnapshot?.let { snapshot ->
            item(key = "snapshot") { SnapshotCard(snapshot, onAdd = { prompt = Prompt.SNAPSHOT }) }
        }
        item(key = "title") { TitleEditor(task, aiMarked = TaskField.TITLE in ai, onSave = viewModel::setTitle) }
        item(key = "actions") {
            Actions(
                task = task,
                onToggleDone = viewModel::toggleDone,
                onStart = viewModel::start,
                onPause = { prompt = Prompt.PAUSE },
                onPostpone = { prompt = Prompt.POSTPONE },
                onAddToPlan = viewModel::addToPlan,
                onSplit = { prompt = Prompt.SPLIT },
                onSnapshot = { prompt = Prompt.SNAPSHOT },
                onArchive = viewModel::archive,
                onRestore = viewModel::restore,
                onDelete = { prompt = Prompt.DELETE },
            )
        }
        if (ai.isNotEmpty()) {
            item(key = "ai") {
                ReasonLabel(
                    text = stringResource(R.string.task_ai_filled, ai.map { Reasons.field(it) }.joinToString(", ")),
                    icon = Icons.Outlined.AutoAwesome,
                    color = TaskerTheme.colors.ai,
                    modifier = Modifier
                        .padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.xs)
                        .clickable(onClickLabel = stringResource(R.string.task_ai_undo_all)) { viewModel.undoAi() },
                )
            }
        }
        item(key = "fields-header") { SectionHeader(stringResource(R.string.task_fields)) }
        item(key = "bucket") {
            FieldRow(
                icon = Icons.Outlined.Inbox,
                label = stringResource(R.string.task_field_bucket),
                value = Formats.bucket(task.bucket),
                placeholder = "",
                onClick = { editor = Editor.BUCKET },
                aiMarked = TaskField.BUCKET in ai,
                onUndoAi = { viewModel.undoAi(TaskField.BUCKET) },
            )
        }
        item(key = "plan-date") {
            FieldRow(
                icon = Icons.Outlined.Event,
                label = stringResource(R.string.task_field_plan_date),
                value = task.planDate?.let { Formats.weekdayDate(it) },
                placeholder = stringResource(R.string.task_not_set),
                onClick = { editor = Editor.PLAN_DATE },
                aiMarked = TaskField.PLAN_DATE in ai,
                onUndoAi = { viewModel.undoAi(TaskField.PLAN_DATE) },
                onClear = { viewModel.setPlanDate(null) },
            )
        }
        item(key = "deadline") {
            val deadline = task.deadline
            val overdue = TaskTiming.isOverdue(task, context.now, context.today, context.zone)
            FieldRow(
                icon = Icons.Outlined.Flag,
                label = stringResource(R.string.task_field_deadline),
                value = deadline?.let { deadlineText(it) },
                placeholder = stringResource(R.string.task_not_set),
                valueColor = if (overdue) TaskerTheme.colors.overdue else androidx.compose.ui.graphics.Color.Unspecified,
                onClick = { editor = Editor.DEADLINE_DATE },
                aiMarked = TaskField.DEADLINE in ai,
                onUndoAi = { viewModel.undoAi(TaskField.DEADLINE) },
                onClear = { viewModel.setDeadline(null) },
            )
        }
        if (task.deadline != null) {
            item(key = "reminders") {
                FieldRow(
                    icon = Icons.Outlined.Alarm,
                    label = stringResource(R.string.task_field_reminders),
                    value = remindersText(
                        task.reminderOffsets?.minutesBefore ?: state.settings.deadlineReminderMinutes,
                        task.reminderOffsets == null,
                    ),
                    placeholder = "",
                    onClick = { editor = Editor.REMINDERS },
                )
            }
        }
        item(key = "estimate") {
            FieldRow(
                icon = Icons.Outlined.Timer,
                label = stringResource(R.string.task_field_estimate),
                value = task.estimate?.let { estimateText(it, state.settings.estimateMinutes.of(it)) },
                placeholder = stringResource(R.string.task_estimate_unset),
                onClick = { editor = Editor.ESTIMATE },
                aiMarked = TaskField.ESTIMATE in ai,
                onUndoAi = { viewModel.undoAi(TaskField.ESTIMATE) },
                onClear = { viewModel.setEstimate(null) },
            )
        }
        item(key = "project") {
            FieldRow(
                icon = Icons.Outlined.Folder,
                label = stringResource(R.string.task_field_project),
                value = detail.project?.name,
                placeholder = stringResource(UiR.string.project_none),
                onClick = { editor = Editor.PROJECT },
                aiMarked = TaskField.PROJECT in ai,
                onUndoAi = { viewModel.undoAi(TaskField.PROJECT) },
            )
            detail.project?.let { project ->
                TextButton(
                    onClick = { onOpenProject(project.id) },
                    modifier = Modifier.padding(start = 56.dp),
                ) { Text(stringResource(R.string.task_open_project)) }
            }
        }
        item(key = "tags") {
            FieldRow(
                icon = Icons.Outlined.Sell,
                label = stringResource(R.string.task_field_tags),
                value = task.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "@$it" },
                placeholder = stringResource(R.string.task_not_set),
                onClick = { editor = Editor.TAGS },
                aiMarked = TaskField.TAGS in ai,
                onUndoAi = { viewModel.undoAi(TaskField.TAGS) },
                onClear = { viewModel.setTags(emptyList()) },
            )
        }
        item(key = "note") { NoteEditor(task.note.orEmpty(), onSave = viewModel::setNote) }
        if (detail.sources.isNotEmpty()) {
            item(key = "sources-header") { SectionHeader(stringResource(R.string.task_sources)) }
            items(detail.sources, key = { "source-${it.id}" }) { source -> SourceRow(source) }
        }
        if (detail.snapshots.size > 1) {
            item(key = "snapshots-header") { SectionHeader(stringResource(R.string.task_snapshots)) }
            items(detail.snapshots.drop(1), key = { "snapshot-${it.id}" }) { snapshot ->
                ListItem(
                    headlineContent = { Text(snapshot.text) },
                    supportingContent = { Text(momentText(snapshot.createdAt)) },
                    leadingContent = { Icon(Icons.AutoMirrored.Outlined.Notes, contentDescription = null) },
                )
            }
        }
        if (detail.history.isNotEmpty()) {
            item(key = "history-header") { SectionHeader(stringResource(R.string.task_history)) }
            items(detail.history, key = { "event-${it.id}" }) { event -> HistoryRow(event) }
        }
    }

    when (editor) {
        Editor.BUCKET -> ChoiceDialog(
            title = stringResource(R.string.task_field_bucket),
            options = listOf<Bucket?>(null, Bucket.TODAY, Bucket.WEEK, Bucket.SOMEDAY).map { Formats.bucket(it) to it },
            selected = task.bucket,
            onDismiss = { editor = null },
            onPick = {
                editor = null
                viewModel.setBucket(it)
            },
        )
        Editor.PLAN_DATE -> DateDialog(
            initial = task.planDate ?: context.today,
            onDismiss = { editor = null },
            onConfirm = {
                editor = null
                viewModel.setPlanDate(it)
            },
            onClear = {
                editor = null
                viewModel.setPlanDate(null)
            },
        )
        Editor.DEADLINE_DATE -> DateDialog(
            initial = task.deadline?.date ?: context.today,
            onDismiss = { editor = null },
            onConfirm = {
                pendingDeadlineDate = it
                editor = Editor.DEADLINE_TIME
            },
            onClear = {
                editor = null
                viewModel.setDeadline(null)
            },
        )
        Editor.DEADLINE_TIME -> {
            val date = pendingDeadlineDate ?: task.deadline?.date ?: context.today
            TimeDialog(
                initial = task.deadline?.time,
                onDismiss = {
                    editor = null
                    viewModel.setDeadline(Deadline(date, task.deadline?.time, task.deadline?.zone))
                },
                onConfirm = { time ->
                    editor = null
                    viewModel.setDeadline(Deadline(date, time, context.zone))
                },
                onClear = {
                    editor = null
                    viewModel.setDeadline(Deadline(date))
                },
            )
        }
        Editor.ESTIMATE -> ChoiceDialog(
            title = stringResource(R.string.task_field_estimate),
            options = listOf<Estimate?>(Estimate.S, Estimate.M, Estimate.L, null).map { estimate ->
                (
                    estimate?.let {
                        estimateText(it, state.settings.estimateMinutes.of(it))
                    } ?: stringResource(R.string.task_estimate_unset)
                    ) to
                    estimate
            },
            selected = task.estimate,
            onDismiss = { editor = null },
            onPick = {
                editor = null
                viewModel.setEstimate(it)
            },
        )
        Editor.PROJECT -> ProjectPickerDialog(
            projects = state.projects,
            current = task.projectId,
            onDismiss = { editor = null },
            onPick = {
                editor = null
                viewModel.setProject(it)
            },
            onCreate = {
                editor = null
                viewModel.moveToNewProject(it)
            },
        )
        Editor.TAGS -> TagsDialog(
            initial = task.tags,
            onDismiss = { editor = null },
            onSave = {
                editor = null
                viewModel.setTags(it)
            },
        )
        Editor.REMINDERS -> RemindersDialog(
            initial = task.reminderOffsets?.minutesBefore,
            defaults = state.settings.deadlineReminderMinutes,
            label = { reminderLabel(it) },
            onDismiss = { editor = null },
            onSave = { offsets ->
                editor = null
                viewModel.setReminders(offsets?.let { ReminderOffsets(it) })
            },
        )
        null -> Unit
    }

    when (prompt) {
        Prompt.PAUSE -> PauseDialog(task, onDismiss = { prompt = null }, onPause = {
            prompt = null
            viewModel.pause(it)
        })
        Prompt.POSTPONE -> PostponeSheet(
            onDismiss = { prompt = null },
            onSelect = {
                prompt = null
                viewModel.postpone(it)
            },
            hasDeadline = task.deadline != null,
        )
        Prompt.SPLIT -> SplitDialog(task, onDismiss = { prompt = null }, onSplit = {
            prompt = null
            viewModel.split(it)
        })
        Prompt.SNAPSHOT -> SnapshotDialog(onDismiss = { prompt = null }, onSave = {
            prompt = null
            viewModel.addSnapshot(it)
        })
        Prompt.DELETE -> ConfirmDialog(
            title = stringResource(R.string.task_delete_title),
            text = stringResource(R.string.task_delete_body),
            confirmLabel = stringResource(R.string.task_delete_confirm),
            onConfirm = {
                prompt = null
                viewModel.deletePermanently(onBack)
            },
            onDismiss = { prompt = null },
        )
        null -> Unit
    }
}

@Composable
private fun SnapshotCard(snapshot: ContextSnapshot, onAdd: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(TaskerTheme.spacing.l),
    ) {
        Column(Modifier.padding(TaskerTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.xs)) {
            Text(stringResource(R.string.task_snapshot_title), style = MaterialTheme.typography.labelLarge)
            Text(snapshot.text, style = MaterialTheme.typography.bodyLarge)
            snapshot.nextStep?.let { Text(stringResource(R.string.task_next_step, it), style = MaterialTheme.typography.bodyMedium) }
            Text(momentText(snapshot.createdAt), style = MaterialTheme.typography.bodySmall, color = TaskerTheme.colors.muted)
            TextButton(onClick = onAdd) { Text(stringResource(R.string.task_add_snapshot)) }
        }
    }
}

@Composable
private fun TitleEditor(task: Task, aiMarked: Boolean, onSave: (String) -> Unit) {
    var text by remember(task.title) { mutableStateOf(task.title) }
    Column(Modifier.padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.s)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { heading() },
            textStyle = MaterialTheme.typography.titleLarge,
            label = { Text(stringResource(R.string.task_field_title)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(text) }),
            trailingIcon = if (text != task.title && text.isNotBlank()) {
                {
                    IconButton(onClick = {
                        onSave(text)
                    }) { Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.task_save)) }
                }
            } else {
                null
            },
        )
        if (aiMarked) ReasonLabel(stringResource(R.string.task_title_ai), icon = Icons.Outlined.AutoAwesome, color = TaskerTheme.colors.ai)
    }
}

@Composable
@Suppress("LongParameterList")
private fun Actions(
    task: Task,
    onToggleDone: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onPostpone: () -> Unit,
    onAddToPlan: () -> Unit,
    onSplit: () -> Unit,
    onSnapshot: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.s),
        horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s),
    ) {
        when (task.status) {
            TaskStatus.ARCHIVED -> {
                Button(onClick = onRestore) { ActionLabel(Icons.Outlined.Restore, stringResource(UiR.string.action_restore)) }
                OutlinedButton(onClick = onDelete) {
                    ActionLabel(Icons.Outlined.DeleteForever, stringResource(R.string.task_delete_confirm))
                }
            }
            TaskStatus.DONE -> {
                Button(onClick = onToggleDone) { ActionLabel(Icons.Outlined.Undo, stringResource(UiR.string.action_mark_not_done)) }
                OutlinedButton(onClick = onArchive) { ActionLabel(Icons.Outlined.Archive, stringResource(UiR.string.action_archive)) }
            }
            else -> {
                Button(onClick = onToggleDone) { ActionLabel(Icons.Outlined.Check, stringResource(UiR.string.action_done)) }
                when (task.status) {
                    TaskStatus.IN_PROGRESS -> FilledTonalButton(onClick = onPause) {
                        ActionLabel(Icons.Outlined.Pause, stringResource(UiR.string.action_pause))
                    }
                    TaskStatus.PAUSED -> FilledTonalButton(onClick = onStart) {
                        ActionLabel(Icons.Outlined.PlayArrow, stringResource(UiR.string.action_resume))
                    }
                    else -> FilledTonalButton(onClick = onStart) {
                        ActionLabel(Icons.Outlined.PlayArrow, stringResource(UiR.string.action_start))
                    }
                }
                OutlinedButton(onClick = onPostpone) { ActionLabel(Icons.Outlined.Snooze, stringResource(UiR.string.action_postpone)) }
                OutlinedButton(onClick = onAddToPlan) {
                    ActionLabel(Icons.Outlined.PlaylistAdd, stringResource(UiR.string.action_add_to_plan))
                }
                OutlinedButton(onClick = onSnapshot) {
                    ActionLabel(Icons.AutoMirrored.Outlined.Notes, stringResource(R.string.task_add_snapshot))
                }
                OutlinedButton(onClick = onSplit) { ActionLabel(Icons.Outlined.CallSplit, stringResource(UiR.string.action_split)) }
                OutlinedButton(onClick = onArchive) { ActionLabel(Icons.Outlined.Archive, stringResource(UiR.string.action_archive)) }
            }
        }
    }
}

@Composable
private fun ActionLabel(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.padding(end = TaskerTheme.spacing.s))
    Text(text)
}

@Composable
private fun NoteEditor(note: String, onSave: (String) -> Unit) {
    var text by remember(note) { mutableStateOf(note) }
    Column(Modifier.padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.s)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp),
            label = { Text(stringResource(R.string.task_field_note)) },
        )
        if (text != note) {
            TextButton(onClick = { onSave(text) }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.task_save)) }
        }
    }
}

@Composable
private fun SourceRow(source: Source) {
    val context = LocalContext.current
    val (title, icon) = when (source.kind) {
        SourceKind.APP -> appLabel(context, source.appPackage) to Icons.Outlined.AppShortcut
        else -> (source.titleSnapshot ?: source.url ?: source.externalId.orEmpty()) to Icons.Outlined.Link
    }
    ListItem(
        headlineContent = { Text(title, maxLines = 2) },
        supportingContent = source.url?.takeIf { it != title }?.let { url -> { Text(url, maxLines = 1) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(enabled = source.url != null) { source.url?.let { openUrl(context, it) } },
    )
}

@Composable
private fun HistoryRow(event: Event) {
    val actor = when (event.actor) {
        Actor.USER -> null
        Actor.AI -> stringResource(UiR.string.history_by_ai)
        Actor.RULE, Actor.INTEGRATION -> stringResource(UiR.string.history_by_rule)
    }
    ListItem(
        headlineContent = {
            Text(listOfNotNull(Reasons.history(event.type, event.changes), actor).joinToString(" · "))
        },
        supportingContent = {
            Column {
                event.reason?.let { Text(Reasons.automation(it)) }
                Text(momentText(event.createdAt), color = TaskerTheme.colors.muted)
            }
        },
    )
}

@Composable
private fun SnapshotDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_add_snapshot)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
                placeholder = { Text(stringResource(UiR.string.pause_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.task_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) } },
    )
}

@Composable
private fun deadlineText(deadline: Deadline): String {
    val context = LocalDayContext.current
    val local = Formats.deadline(deadline)
    val zone = deadline.zone
    if (deadline.time == null || zone == null || zone == context.zone) return local
    // §7.8: a timed deadline created in another zone also shows its original wall-clock time.
    val original = ZonedDateTime.of(deadline.date, deadline.time, zone)
    return stringResource(R.string.task_deadline_other_zone, local, Formats.time(original.toLocalTime()), zone.id)
}

@Composable
private fun estimateText(estimate: Estimate, minutes: Int): String = "${Formats.estimate(estimate)} · ${Formats.duration(minutes)}"

@Composable
private fun remindersText(minutes: List<Int>, isDefault: Boolean): String {
    val list = minutes.sortedDescending().map {
        reminderLabel(it)
    }.joinToString(", ").ifEmpty { stringResource(R.string.task_reminders_none) }
    return if (isDefault) stringResource(R.string.task_reminders_default_value, list) else list
}

@Composable
private fun reminderLabel(minutes: Int): String = when (minutes) {
    0 -> stringResource(R.string.task_reminder_at_deadline)
    else -> stringResource(R.string.task_reminder_before, Formats.duration(minutes))
}

@Composable
private fun momentText(instant: java.time.Instant): String {
    val context = LocalDayContext.current
    val local = instant.atZone(context.zone)
    return stringResource(UiR.string.day_at_time, Formats.day(local.toLocalDate()), Formats.time(local.toLocalTime()))
}

private fun appLabel(context: Context, packageName: String?): String {
    if (packageName == null) return ""
    return try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing can open this link; the URL stays visible in the card.
    }
}
