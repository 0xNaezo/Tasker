package app.tasker.feature.journal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tasker.core.data.repository.JournalBatch
import app.tasker.core.data.repository.JournalEntry
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.model.Actor
import app.tasker.core.model.EntityType
import app.tasker.core.model.TaskId
import app.tasker.core.ui.DayContext
import app.tasker.core.ui.LocalDayContext
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.component.Banner
import app.tasker.core.ui.component.EmptyState
import app.tasker.core.ui.component.ReasonLabel
import app.tasker.core.ui.component.SectionHeader
import app.tasker.core.ui.format.Formats
import app.tasker.core.ui.format.Reasons
import java.time.LocalDate

/**
 * "Automation journal" (§14.2, §11.4, AUT-1): what the app did on its own — rules, AI, integrations — by day, newest
 * first. Every batch says when, what, with which tasks and why; it is undone as a whole or task by task for 30 days,
 * older batches stay visible without the button. The day's AI fills are folded into one row.
 */
@Composable
fun JournalScreen(
    onBack: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JournalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onOpenTask) {
        JournalActions(
            onToggle = viewModel::toggle,
            onToggleAi = viewModel::toggleAi,
            onUndoBatch = viewModel::undoBatch,
            onUndoEntry = viewModel::undoEntry,
            onDismissKept = viewModel::dismissKept,
            onOpenTask = onOpenTask,
        )
    }
    JournalContent(state = state, actions = actions, onBack = onBack, modifier = modifier)
}

/** What the journal rows can do. */
internal class JournalActions(
    val onToggle: (JournalBatch) -> Unit,
    val onToggleAi: (LocalDate) -> Unit,
    val onUndoBatch: (JournalBatch) -> Unit,
    val onUndoEntry: (JournalBatch, JournalEntry) -> Unit,
    val onDismissKept: () -> Unit,
    val onOpenTask: (TaskId) -> Unit,
)

/** Stateless body of [JournalScreen]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JournalContent(state: JournalUiState, actions: JournalActions, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.journal_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.journal_back))
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            state.rows.isEmpty() -> EmptyState(
                title = stringResource(R.string.journal_empty_title),
                body = stringResource(R.string.journal_empty_body),
                icon = Icons.Outlined.History,
                modifier = Modifier.padding(padding),
            )
            else -> JournalList(state, actions, padding)
        }
    }
}

@Composable
private fun JournalList(state: JournalUiState, actions: JournalActions, padding: PaddingValues) {
    val context = LocalDayContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item(key = "intro", contentType = "intro") {
            Text(
                text = stringResource(R.string.journal_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.s),
            )
        }
        if (state.kept.isNotEmpty()) {
            item(key = "kept", contentType = "kept") { KeptBanner(state.kept, actions) }
        }
        items(state.rows, key = { it.key }, contentType = { it.contentType }) { row ->
            when (row) {
                is JournalRow.Day -> SectionHeader(dayTitle(row.day, context.today))
                is JournalRow.Batch -> BatchCard(
                    batch = row.batch,
                    context = context,
                    expanded = row.batch.batchId in state.expanded,
                    busy = row.batch.batchId in state.busy,
                    actions = actions,
                )
                is JournalRow.AiGroup -> AiGroupRow(row, onToggle = { actions.onToggleAi(row.day) })
            }
        }
    }
}

/** One batch: time and actor, what was done to how many tasks, why, the tasks, and undo (AUT-1). */
@Composable
internal fun BatchCard(batch: JournalBatch, context: DayContext, expanded: Boolean, busy: Boolean, actions: JournalActions) {
    val entries = batch.entries
    val multiple = entries.size > 1
    val reasons = entries.map { entry -> entry.event.reason?.let { Reasons.automation(it) } }
    // One shared reason is said once for the batch; different ones go under each task.
    val sharedReason = reasons.distinct().singleOrNull()
    val undoState = JournalRows.undoState(batch, context.now)
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.xs),
    ) {
        Column(
            modifier = Modifier.padding(start = TaskerTheme.spacing.l, end = TaskerTheme.spacing.s, top = TaskerTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.xs)) {
                Icon(
                    actorIcon(batch.actor),
                    contentDescription = null,
                    modifier = Modifier.size(SMALL_ICON),
                    tint = TaskerTheme.colors.muted,
                )
                Text(
                    text = stringResource(
                        R.string.journal_time_actor,
                        Formats.time(batch.createdAt.atZone(context.zone).toLocalTime()),
                        actorLabel(batch.actor),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = batchTitle(batch),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (sharedReason != null) ReasonLabel(sharedReason)
            val shown = if (expanded || !multiple) entries else entries.take(COLLAPSED_ENTRIES)
            shown.forEachIndexed { index, entry ->
                EntryRow(
                    entry = entry,
                    reason = if (sharedReason == null) reasons[index] else null,
                    state = JournalRows.undoState(entry, context.now),
                    showUndo = expanded && multiple,
                    // A fully undone batch says it once in the footer.
                    markUndone = undoState != UndoState.UNDONE,
                    busy = busy,
                    onUndo = { actions.onUndoEntry(batch, entry) },
                    onOpenTask = actions.onOpenTask,
                )
            }
            val hidden = entries.size - shown.size
            if (hidden > 0) {
                Text(
                    text = pluralStringResource(R.plurals.journal_more, hidden, hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BatchFooter(
                state = undoState,
                multiple = multiple,
                expanded = expanded,
                busy = busy,
                onToggle = { actions.onToggle(batch) },
                onUndo = { actions.onUndoBatch(batch) },
            )
        }
    }
}

@Composable
private fun EntryRow(
    entry: JournalEntry,
    reason: String?,
    state: UndoState,
    showUndo: Boolean,
    markUndone: Boolean,
    busy: Boolean,
    onUndo: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
) {
    val isTask = entry.event.entityType == EntityType.TASK
    val title = entry.title.ifBlank { stringResource(R.string.journal_untitled) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val open = stringResource(UiR.string.action_open)
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = TOUCH_TARGET)
                .then(if (isTask) Modifier.clickable(onClickLabel = open) { onOpenTask(entry.event.entityId) } else Modifier)
                .padding(vertical = TaskerTheme.spacing.xs),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.xs)) {
                if (!isTask) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = stringResource(R.string.journal_project),
                        modifier = Modifier.size(SMALL_ICON),
                        tint = TaskerTheme.colors.muted,
                    )
                }
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (reason != null) ReasonLabel(reason)
        }
        when {
            state == UndoState.UNDONE && markUndone -> UndoneLabel()
            showUndo && state == UndoState.AVAILABLE -> TextButton(onClick = onUndo, enabled = !busy) {
                Text(stringResource(UiR.string.action_undo))
            }
            else -> Unit
        }
    }
}

@Composable
private fun BatchFooter(state: UndoState, multiple: Boolean, expanded: Boolean, busy: Boolean, onToggle: () -> Unit, onUndo: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = TaskerTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multiple) {
            TextButton(onClick = onToggle) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                Text(stringResource(if (expanded) R.string.journal_hide_details else R.string.journal_details))
            }
        }
        Spacer(Modifier.weight(1f))
        when (state) {
            UndoState.AVAILABLE, UndoState.PARTLY_UNDONE -> {
                if (state == UndoState.PARTLY_UNDONE) {
                    Text(
                        text = stringResource(R.string.journal_partly_undone),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onUndo, enabled = !busy, modifier = Modifier.padding(start = TaskerTheme.spacing.s)) {
                    Text(stringResource(if (multiple) R.string.journal_undo_all else UiR.string.action_undo))
                }
            }
            UndoState.UNDONE -> UndoneLabel(Modifier.heightIn(min = TOUCH_TARGET))
            UndoState.EXPIRED -> Text(
                text = stringResource(R.string.journal_expired),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = TaskerTheme.spacing.m),
            )
        }
    }
}

@Composable
private fun UndoneLabel(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = TaskerTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.xs),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.Undo,
            contentDescription = null,
            modifier = Modifier.size(SMALL_ICON),
            tint = TaskerTheme.colors.muted,
        )
        Text(stringResource(R.string.journal_undone), style = MaterialTheme.typography.labelMedium, color = TaskerTheme.colors.muted)
    }
}

/** The day's AI fills as one row; the batches follow it when it is open. */
@Composable
private fun AiGroupRow(row: JournalRow.AiGroup, onToggle: () -> Unit) {
    val stateLabel = stringResource(if (row.expanded) R.string.journal_expanded else R.string.journal_collapsed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TOUCH_TARGET)
            .clickable(
                onClickLabel = stringResource(if (row.expanded) R.string.journal_ai_hide else R.string.journal_ai_show),
                onClick = onToggle,
            )
            .semantics { stateDescription = stateLabel }
            .padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.m),
    ) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = TaskerTheme.colors.ai)
        Text(
            text = pluralStringResource(R.plurals.journal_ai_group, row.taskCount, row.taskCount),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(if (row.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
    }
}

/** Interpretation 16: the user's later changes were kept; offer to open the task. */
@Composable
private fun KeptBanner(kept: List<KeptTask>, actions: JournalActions) {
    val untitled = stringResource(R.string.journal_untitled)
    val single = kept.singleOrNull()
    Banner(
        text = stringResource(R.string.journal_kept, kept.joinToString(", ") { it.title.ifBlank { untitled } }),
        icon = Icons.Outlined.Info,
        actionLabel = single?.let { stringResource(R.string.journal_open_task) },
        onAction = single?.let { task -> { actions.onOpenTask(task.taskId) } },
        onDismiss = actions.onDismissKept,
        modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.xs),
    )
}

/** "Sent to review · 3 tasks": the action the entries share and what it touched; one entry needs no count. */
@Composable
private fun batchTitle(batch: JournalBatch): String {
    val action = batch.entries.map { Reasons.history(it.event.type, it.event.changes) }.distinct().singleOrNull()
        ?: stringResource(R.string.journal_several_actions)
    if (batch.entries.size <= 1) return action
    val counts = JournalRows.counts(batch)
    val what = buildList {
        if (counts.tasks > 0) add(pluralStringResource(R.plurals.journal_tasks, counts.tasks, counts.tasks))
        if (counts.projects > 0) add(pluralStringResource(R.plurals.journal_projects, counts.projects, counts.projects))
    }.joinToString(", ")
    return if (what.isEmpty()) action else stringResource(R.string.journal_batch_title, action, what)
}

@Composable
private fun actorLabel(actor: Actor): String = stringResource(
    when (actor) {
        Actor.AI -> R.string.journal_actor_ai
        Actor.INTEGRATION -> R.string.journal_actor_integration
        Actor.RULE, Actor.USER -> R.string.journal_actor_rule
    },
)

private fun actorIcon(actor: Actor): ImageVector = when (actor) {
    Actor.AI -> Icons.Outlined.AutoAwesome
    Actor.INTEGRATION -> Icons.Outlined.Sync
    Actor.RULE, Actor.USER -> Icons.AutoMirrored.Outlined.Rule
}

/** "Today", "Yesterday", otherwise "Sunday, 4 October". */
@Composable
private fun dayTitle(day: LocalDate, today: LocalDate): String {
    val locale = Formats.locale()
    return when (day) {
        today -> stringResource(UiR.string.day_today).replaceFirstChar { it.titlecase(locale) }
        today.minusDays(1) -> stringResource(UiR.string.day_yesterday).replaceFirstChar { it.titlecase(locale) }
        else -> Formats.fullDate(day)
    }
}

/** Entries shown in a folded batch; the rest are behind "Details". */
private const val COLLAPSED_ENTRIES = 3
private val SMALL_ICON = 16.dp
private val TOUCH_TARGET = 48.dp
