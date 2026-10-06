package app.tasker.feature.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tasker.core.data.plan.DayView
import app.tasker.core.data.plan.PlanEntry
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.domain.plan.PlanChange
import app.tasker.core.model.Task
import app.tasker.core.model.TaskId
import app.tasker.core.model.TaskStatus
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.component.Banner
import app.tasker.core.ui.component.CapacityBar
import app.tasker.core.ui.component.DragHandle
import app.tasker.core.ui.component.DraggableItem
import app.tasker.core.ui.component.EmptyState
import app.tasker.core.ui.component.PauseDialog
import app.tasker.core.ui.component.ReasonLabel
import app.tasker.core.ui.component.RowAction
import app.tasker.core.ui.component.SectionHeader
import app.tasker.core.ui.component.SplitDialog
import app.tasker.core.ui.component.TaskRow
import app.tasker.core.ui.component.WipLimitDialog
import app.tasker.core.ui.component.rememberDragDropState
import app.tasker.core.ui.format.Formats
import app.tasker.core.ui.format.Reasons

/**
 * "Day plan" (§10.5, §14.2): the plan with reasons, "Doesn't fit" with actions and "Needs a decision" (DAT-3
 * questions, review and inbox triage) — all the questions of the day in one batch (principle 2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    onBack: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    onOpenReview: () -> Unit,
    onOpenInbox: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val wip by viewModel.wipOutcome.collectAsStateWithLifecycle()
    val view = state.view
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.plan_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.plan_back))
                    }
                },
            )
        },
        bottomBar = {
            if (view != null && !view.isAccepted && state.prepared) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = viewModel::accept,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(TaskerTheme.spacing.l),
                    ) { Text(stringResource(R.string.plan_accept)) }
                }
            }
        },
    ) { padding ->
        if (view == null || !state.prepared) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            PlanContent(
                view = view,
                change = state.change,
                projectNames = state.projectNames,
                viewModel = viewModel,
                onOpenTask = onOpenTask,
                onOpenReview = onOpenReview,
                onOpenInbox = onOpenInbox,
                modifier = Modifier.padding(padding),
            )
        }
    }
    wip?.let { outcome ->
        WipLimitDialog(outcome.task, outcome.inProgress, outcome.limit, onPause = viewModel::pauseOther, onDismiss = viewModel::dismissWip)
    }
}

@Composable
@Suppress("LongMethod")
private fun PlanContent(
    view: DayView,
    change: PlanChange?,
    projectNames: Map<String, String>,
    viewModel: PlanViewModel,
    onOpenTask: (TaskId) -> Unit,
    onOpenReview: () -> Unit,
    onOpenInbox: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pausing by remember { mutableStateOf<Task?>(null) }
    var splitting by remember { mutableStateOf<Task?>(null) }
    var replacing by remember { mutableStateOf<Task?>(null) }
    val entries = view.entries
    // Local order while dragging; committed on drop.
    var order by remember(entries.map { it.task.id }) { mutableStateOf(entries.map { it.task.id }) }
    val byId = entries.associateBy { it.task.id }
    var moved by remember { mutableStateOf<TaskId?>(null) }
    val listState = rememberLazyListState()
    val dragState = rememberDragDropState(
        listState = listState,
        onMove = { from, to ->
            val fromId = (from as? String)?.removePrefix(PLAN_KEY)
            val toId = (to as? String)?.removePrefix(PLAN_KEY)
            if (fromId == null || toId == null || fromId !in byId || toId !in byId) return@rememberDragDropState false
            order = order.toMutableList().apply { add(indexOf(toId), removeAt(indexOf(fromId))) }
            moved = fromId
            true
        },
        onDrop = {
            val id = moved ?: return@rememberDragDropState
            moved = null
            byId[id]?.let { viewModel.reorder(it.task, order) }
        },
    )
    fun move(task: Task, delta: Int) {
        val index = order.indexOf(task.id)
        val target = (index + delta).coerceIn(0, order.lastIndex)
        if (index < 0 || target == index) return
        val updated = order.toMutableList().apply { add(target, removeAt(index)) }
        order = updated
        viewModel.reorder(task, updated)
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        item(key = "capacity") {
            Column(
                Modifier.padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.m),
                verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s),
            ) {
                CapacityBar(plannedMinutes = view.plannedMinutes, capacity = view.capacity)
                Text(
                    text = stringResource(if (view.isAccepted) R.string.plan_accepted_state else R.string.plan_draft_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (change != null) ChangeNote(change, onDismiss = viewModel::dismissChange)
            }
        }
        if (order.isEmpty()) {
            item(key = "empty") { EmptyState(title = stringResource(R.string.plan_empty)) }
        }
        items(order.mapNotNull { byId[it] }, key = { PLAN_KEY + it.task.id }) { entry ->
            DraggableItem(dragState, key = PLAN_KEY + entry.task.id) {
                PlanRow(
                    entry = entry,
                    projectName = entry.task.projectId?.let { projectNames[it] },
                    viewModel = viewModel,
                    onOpenTask = onOpenTask,
                    onPause = { pausing = it },
                    onSplit = { splitting = it },
                    onMove = ::move,
                    handle = { DragHandle(dragState, key = PLAN_KEY + entry.task.id) },
                )
            }
        }
        val doesNotFit = view.doesNotFit
        if (doesNotFit.isNotEmpty()) {
            item(key = "nofit-header") {
                SectionHeader(stringResource(R.string.plan_does_not_fit)) {
                    TextButton(onClick = { viewModel.postponeAll(doesNotFit.map { it.task }) }) {
                        Text(stringResource(R.string.plan_all_tomorrow))
                    }
                }
            }
            items(doesNotFit, key = { "nofit-${it.task.id}" }) { candidate ->
                TaskRow(
                    task = candidate.task,
                    onClick = { onOpenTask(candidate.task.id) },
                    onComplete = { viewModel.toggleDone(candidate.task) },
                    onPostpone = { viewModel.postpone(candidate.task, it) },
                    projectName = candidate.task.projectId?.let { projectNames[it] },
                    reason = Reasons.candidate(candidate.reason),
                    actions = listOf(
                        RowAction(stringResource(R.string.plan_add_anyway), Icons.Outlined.PlaylistAdd) { viewModel.add(candidate.task) },
                        RowAction(stringResource(R.string.plan_replace), Icons.Outlined.SwapVert) { replacing = candidate.task },
                    ),
                )
            }
        }
        val planned = order.toSet() + doesNotFit.map { it.task.id }
        val others = view.candidates.filter { it.task.id !in planned }
        if (others.isNotEmpty()) {
            item(key = "others-header") { SectionHeader(stringResource(R.string.plan_other_candidates)) }
            items(others, key = { "other-${it.task.id}" }) { candidate ->
                TaskRow(
                    task = candidate.task,
                    onClick = { onOpenTask(candidate.task.id) },
                    onComplete = { viewModel.toggleDone(candidate.task) },
                    onPostpone = { viewModel.postpone(candidate.task, it) },
                    projectName = candidate.task.projectId?.let { projectNames[it] },
                    reason = Reasons.candidate(candidate.reason),
                    actions = listOf(
                        RowAction(stringResource(UiR.string.action_add_to_plan), Icons.Outlined.PlaylistAdd) {
                            viewModel.add(candidate.task)
                        },
                    ),
                )
            }
        }
        if (view.questions.isNotEmpty() || view.reviewCount > 0 || view.inboxOverflow) {
            item(key = "decisions-header") { SectionHeader(stringResource(R.string.plan_needs_decision)) }
        }
        items(view.questions, key = { "question-${it.id}" }) { task ->
            QuestionCard(
                task = task,
                onOpen = { onOpenTask(task.id) },
                onSplit = { splitting = task },
                onSomeday = { viewModel.toSomeday(task) },
                onArchive = { viewModel.archive(task) },
                onKeep = { viewModel.keep(task) },
            )
        }
        if (view.reviewCount > 0) {
            item(key = "review") {
                Banner(
                    text = pluralStringResource(R.plurals.today_review_banner, view.reviewCount, view.reviewCount),
                    icon = Icons.Outlined.EditNote,
                    actionLabel = stringResource(R.string.today_review_action),
                    onAction = onOpenReview,
                    modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.xs),
                )
            }
        }
        if (view.inboxOverflow) {
            item(key = "inbox") {
                Banner(
                    text = pluralStringResource(R.plurals.today_inbox_banner, view.inboxCount, view.inboxCount),
                    icon = Icons.Outlined.Inbox,
                    actionLabel = stringResource(R.string.today_inbox_action),
                    onAction = onOpenInbox,
                    modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.xs),
                )
            }
        }
    }

    pausing?.let { task ->
        PauseDialog(task, onDismiss = { pausing = null }, onPause = { note ->
            pausing = null
            viewModel.pause(task, note)
        })
    }
    splitting?.let { task ->
        SplitDialog(task, onDismiss = { splitting = null }, onSplit = { steps ->
            splitting = null
            viewModel.split(task, steps)
        })
    }
    replacing?.let { task ->
        ReplaceDialog(
            entries = order.mapNotNull { byId[it] }.filter { !it.isDone },
            onDismiss = { replacing = null },
            onPick = { evict ->
                replacing = null
                viewModel.replace(task, evict)
            },
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun PlanRow(
    entry: PlanEntry,
    projectName: String?,
    viewModel: PlanViewModel,
    onOpenTask: (TaskId) -> Unit,
    onPause: (Task) -> Unit,
    onSplit: (Task) -> Unit,
    onMove: (Task, Int) -> Unit,
    handle: @Composable () -> Unit,
) {
    val task = entry.task
    val actions = buildList {
        when (task.status) {
            TaskStatus.OPEN -> add(RowAction(stringResource(UiR.string.action_start), Icons.Outlined.PlayArrow) { viewModel.start(task) })
            TaskStatus.PAUSED -> add(
                RowAction(stringResource(UiR.string.action_resume), Icons.Outlined.PlayArrow) {
                    viewModel.start(task)
                },
            )
            TaskStatus.IN_PROGRESS -> add(RowAction(stringResource(UiR.string.action_pause), Icons.Outlined.Pause) { onPause(task) })
            else -> Unit
        }
        add(RowAction(stringResource(UiR.string.action_move_up), Icons.Outlined.ArrowUpward) { onMove(task, -1) })
        add(RowAction(stringResource(UiR.string.action_move_down), Icons.Outlined.ArrowDownward) { onMove(task, 1) })
        if (task.status.isActive) add(RowAction(stringResource(UiR.string.action_split), Icons.Outlined.CallSplit) { onSplit(task) })
        add(RowAction(stringResource(UiR.string.action_remove_from_plan), Icons.Outlined.PlaylistRemove) { viewModel.remove(task) })
    }
    Column {
        TaskRow(
            task = task,
            onClick = { onOpenTask(task.id) },
            onComplete = { viewModel.toggleDone(task) },
            onPostpone = { viewModel.postpone(task, it) },
            projectName = projectName,
            reason = entry.reason?.let { Reasons.candidate(it) } ?: stringResource(UiR.string.reason_added_manually),
            actions = actions,
            trailing = handle,
        )
        if (entry.suggestSplit) {
            ReasonLabel(
                text = stringResource(R.string.plan_split_hint),
                modifier = Modifier
                    .padding(start = 56.dp, bottom = TaskerTheme.spacing.s)
                    .clickable { onSplit(task) },
            )
        }
    }
}

/** DAT-3: "split / to Someday / archive / keep". */
@Composable
private fun QuestionCard(
    task: Task,
    onOpen: () -> Unit,
    onSplit: () -> Unit,
    onSomeday: () -> Unit,
    onArchive: () -> Unit,
    onKeep: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.xs),
    ) {
        Column(Modifier.padding(TaskerTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s)) {
            Text(task.title, style = MaterialTheme.typography.titleSmall)
            ReasonLabel(pluralStringResource(R.plurals.plan_question, task.postponeCount, task.postponeCount))
            Text(stringResource(R.string.plan_question_body), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s)) {
                OutlinedButton(onClick = onSplit) { Text(stringResource(UiR.string.action_split)) }
                OutlinedButton(onClick = onSomeday) { Text(stringResource(UiR.string.action_to_someday)) }
                OutlinedButton(onClick = onArchive) { Text(stringResource(UiR.string.action_archive)) }
                TextButton(onClick = onKeep) { Text(stringResource(UiR.string.action_keep)) }
            }
        }
    }
}

@Composable
private fun ChangeNote(change: PlanChange, onDismiss: () -> Unit) {
    val parts = buildList {
        if (change.capacityChanged) {
            add(
                stringResource(
                    R.string.plan_changed_capacity,
                    Formats.duration(change.capacityBeforeMin),
                    Formats.duration(change.capacityAfterMin),
                ),
            )
        }
        if (change.addedTaskIds.isNotEmpty()) {
            add(pluralStringResource(R.plurals.plan_changed_added, change.addedTaskIds.size, change.addedTaskIds.size))
        }
        if (change.removedTaskIds.isNotEmpty()) {
            add(pluralStringResource(R.plurals.plan_changed_removed, change.removedTaskIds.size, change.removedTaskIds.size))
        }
    }
    Banner(text = stringResource(R.string.plan_changed, parts.joinToString(", ")), onDismiss = onDismiss)
}

/** PLN-5: which planned task gives way to the one that does not fit. */
@Composable
private fun ReplaceDialog(entries: List<PlanEntry>, onDismiss: () -> Unit, onPick: (Task) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plan_replace_title)) },
        text = {
            Column {
                entries.forEach { entry ->
                    ListItem(
                        headlineContent = { Text(entry.task.title, maxLines = 2) },
                        supportingContent = { Text(Formats.duration(entry.item.minutes)) },
                        modifier = Modifier.clickable { onPick(entry.task) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) } },
    )
}

private const val PLAN_KEY = "plan-"
