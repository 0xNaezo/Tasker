package app.tasker.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.model.ArchiveReason
import app.tasker.core.model.Task
import app.tasker.core.model.TaskId
import app.tasker.core.model.TaskStatus
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.component.ConfirmDialog
import app.tasker.core.ui.component.EmptyState
import app.tasker.core.ui.component.ReasonLabel
import app.tasker.core.ui.component.TaskChips
import app.tasker.core.ui.format.Formats
import java.time.LocalDate

/**
 * "Archive" (§14.2, §15, ARC-1, TTL-8): archived tasks, the latest archived first, each with why and when it went
 * there and where it returns. "Restore" is one tap with Undo; "Delete forever" asks for confirmation first.
 */
@Composable
fun ArchiveScreen(
    onBack: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArchiveViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val archived = viewModel.archive.collectAsLazyPagingItems()
    ArchiveContent(
        state = state,
        archived = archived,
        onBack = onBack,
        onOpenTask = onOpenTask,
        onRestore = viewModel::restore,
        onDelete = viewModel::askDelete,
        modifier = modifier,
    )
    state.pendingDelete?.let { task ->
        ConfirmDialog(
            title = stringResource(R.string.archive_delete_title),
            text = stringResource(R.string.archive_delete_text, task.title),
            confirmLabel = stringResource(R.string.archive_delete),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

/** Stateless body of [ArchiveScreen]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchiveContent(
    state: ArchiveUiState,
    archived: LazyPagingItems<ArchivedItem>,
    onBack: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    onRestore: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.archive_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.archive_back))
                    }
                },
            )
        },
    ) { padding ->
        val refresh = archived.loadState.refresh
        when {
            archived.itemCount == 0 && refresh is LoadState.Loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            archived.itemCount == 0 && refresh is LoadState.Error -> EmptyState(
                title = stringResource(R.string.archive_error),
                actionLabel = stringResource(R.string.search_retry),
                onAction = archived::retry,
                modifier = Modifier.padding(padding),
            )
            archived.itemCount == 0 -> EmptyState(
                title = stringResource(R.string.archive_empty_title),
                body = stringResource(R.string.archive_empty_body),
                icon = Icons.Outlined.Inventory2,
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                item(key = "summary", contentType = "summary") {
                    Text(
                        text = pluralStringResource(R.plurals.archive_summary, state.count, state.count),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.s),
                    )
                }
                items(
                    count = archived.itemCount,
                    key = archived.itemKey { it.task.id },
                    contentType = archived.itemContentType { "archived" },
                ) { index ->
                    archived[index]?.let { item ->
                        ArchivedRow(
                            item = item,
                            projectName = item.task.projectId?.let { state.projectNames[it] },
                            onOpen = { onOpenTask(item.task.id) },
                            onRestore = { onRestore(item.task) },
                            onDelete = { onDelete(item.task) },
                        )
                    }
                }
            }
        }
    }
}

/** An archived task: title, chips, the bucket it returns to, why and when it was archived; Restore and the menu. */
@Composable
internal fun ArchivedRow(item: ArchivedItem, projectName: String?, onOpen: () -> Unit, onRestore: () -> Unit, onDelete: () -> Unit) {
    val task = item.task
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .clickable(onClickLabel = stringResource(UiR.string.action_open), onClick = onOpen)
            .padding(start = TaskerTheme.spacing.l, top = TaskerTheme.spacing.s, bottom = TaskerTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.xs)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            PlaceChips(task, showStatus = false)
            TaskChips(task = task, projectName = projectName)
            ReasonLabel(text = archiveReason(task.archiveReason, item.archivedOn), icon = Icons.Outlined.Inventory2)
        }
        TextButton(onClick = onRestore) { Text(stringResource(UiR.string.action_restore)) }
        DeleteMenu(onDelete)
    }
}

@Composable
private fun DeleteMenu(onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(UiR.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.archive_delete)) },
                leadingIcon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, modifier = Modifier.size(MENU_ICON_SIZE)) },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

/** "Archived by you · today", "Archived automatically after skips in review · 3 Oct" (principle 7). */
@Composable
private fun archiveReason(reason: ArchiveReason?, day: LocalDate?): String {
    val text = when (reason) {
        ArchiveReason.USER -> stringResource(R.string.archive_reason_user)
        ArchiveReason.TTL_SKIPS -> stringResource(R.string.archive_reason_ttl_skips)
        ArchiveReason.PROJECT_ARCHIVED -> stringResource(R.string.archive_reason_project)
        ArchiveReason.SPLIT -> stringResource(R.string.archive_reason_split)
        ArchiveReason.MERGED -> stringResource(R.string.archive_reason_merged)
        ArchiveReason.TELEGRAM_CANCELLED -> stringResource(R.string.archive_reason_telegram)
        null -> Formats.status(TaskStatus.ARCHIVED)
    }
    return if (day == null) text else "$text · ${Formats.day(day)}"
}

private val ROW_MIN_HEIGHT = 56.dp
private val MENU_ICON_SIZE = 24.dp
