package app.tasker.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import app.tasker.core.ui.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** An extra action of a [TaskRow]: shown in the "⋮" menu and offered to TalkBack as a custom action. */
@Immutable
data class RowAction(val label: String, val icon: ImageVector? = null, val onClick: () -> Unit)

/**
 * Task row (§14.3). Swipe right completes, swipe left opens the postpone menu (EXC-2). Both swipes are duplicated by
 * visible buttons — the check mark at the start and the "⋮" menu — and by TalkBack custom actions.
 *
 * @param reason why the task is here (principle 7), shown under the chips.
 * @param onComplete toggles completion: completes an active task, reopens a done one.
 * @param trailing optional content before the menu button, e.g. a drag handle.
 */
@Composable
fun TaskRow(
    task: Task,
    onClick: () -> Unit,
    onComplete: () -> Unit,
    onPostpone: (PostponeOption) -> Unit,
    modifier: Modifier = Modifier,
    projectName: String? = null,
    reason: String? = null,
    actions: List<RowAction> = emptyList(),
    swipeEnabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    var postponeOpen by rememberSaveable { mutableStateOf(false) }
    val active = task.status.isActive
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    // A completed row usually leaves the list; when it stays (e.g. done items of the day plan), bring it back.
    LaunchedEffect(task) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) state.reset()
    }

    val completeLabel = stringResource(if (active) R.string.action_mark_done else R.string.action_mark_not_done)
    val postponeLabel = stringResource(R.string.action_postpone)
    val a11yActions = buildList {
        add(
            CustomAccessibilityAction(completeLabel) {
                onComplete()
                true
            },
        )
        if (active) {
            add(
                CustomAccessibilityAction(postponeLabel) {
                    postponeOpen = true
                    true
                },
            )
        }
        actions.forEach { action ->
            add(
                CustomAccessibilityAction(action.label) {
                    action.onClick()
                    true
                },
            )
        }
    }

    SwipeToDismissBox(
        state = state,
        modifier = modifier.semantics { customActions = a11yActions },
        enableDismissFromStartToEnd = swipeEnabled && active,
        enableDismissFromEndToStart = swipeEnabled && active,
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onComplete()
                    scope.launch {
                        delay(SWIPE_RESET_FALLBACK_MS)
                        state.reset()
                    }
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    postponeOpen = true
                    scope.launch { state.reset() }
                }
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
        backgroundContent = { SwipeBackground(state.dismissDirection) },
    ) {
        TaskRowContent(
            task = task,
            projectName = projectName,
            reason = reason,
            completeLabel = completeLabel,
            postponeLabel = postponeLabel,
            onClick = onClick,
            onComplete = onComplete,
            onOpenPostpone = { postponeOpen = true },
            actions = actions,
            trailing = trailing,
        )
    }

    if (postponeOpen) {
        PostponeSheet(
            onDismiss = { postponeOpen = false },
            onSelect = { option ->
                postponeOpen = false
                onPostpone(option)
            },
            hasDeadline = task.deadline != null,
        )
    }
}

@Composable
private fun TaskRowContent(
    task: Task,
    projectName: String?,
    reason: String?,
    completeLabel: String,
    postponeLabel: String,
    onClick: () -> Unit,
    onComplete: () -> Unit,
    onOpenPostpone: () -> Unit,
    actions: List<RowAction>,
    trailing: (@Composable () -> Unit)?,
) {
    val done = task.status == TaskStatus.DONE
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClickLabel = stringResource(R.string.action_open), onClick = onClick)
                .padding(vertical = TaskerTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onComplete) {
                if (done) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = completeLabel, tint = TaskerTheme.colors.done)
                } else {
                    Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = completeLabel)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.xs),
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                TaskChips(task = task, projectName = projectName)
                if (reason != null) ReasonLabel(reason)
            }
            trailing?.invoke()
            RowMenu(
                showPostpone = task.status.isActive,
                postponeLabel = postponeLabel,
                onOpenPostpone = onOpenPostpone,
                actions = actions,
            )
        }
    }
}

@Composable
private fun RowMenu(showPostpone: Boolean, postponeLabel: String, onOpenPostpone: () -> Unit, actions: List<RowAction>) {
    if (!showPostpone && actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (showPostpone) {
                DropdownMenuItem(
                    text = { Text(postponeLabel) },
                    leadingIcon = { Icon(Icons.Outlined.Snooze, contentDescription = null) },
                    onClick = {
                        open = false
                        onOpenPostpone()
                    },
                )
            }
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    leadingIcon = action.icon?.let { icon -> { Icon(icon, contentDescription = null) } },
                    onClick = {
                        open = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    val (color, icon, alignment) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Triple(TaskerTheme.colors.done, Icons.Outlined.Check, Alignment.CenterStart)
        SwipeToDismissBoxValue.EndToStart ->
            Triple(MaterialTheme.colorScheme.secondaryContainer, Icons.Outlined.Snooze, Alignment.CenterEnd)
        SwipeToDismissBoxValue.Settled -> Triple(MaterialTheme.colorScheme.surface, null, Alignment.Center)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color)
            .padding(horizontal = TaskerTheme.spacing.xl),
        contentAlignment = alignment,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
        }
    }
}

/** If a completed row stays in the list and the task did not change (e.g. the command failed), restore it. */
private const val SWIPE_RESET_FALLBACK_MS = 1_500L
