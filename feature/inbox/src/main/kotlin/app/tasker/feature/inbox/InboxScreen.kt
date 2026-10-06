package app.tasker.feature.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.model.Bucket
import app.tasker.core.model.Task
import app.tasker.core.model.TaskId
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.component.Banner
import app.tasker.core.ui.component.EmptyState
import app.tasker.core.ui.component.ProjectPickerDialog
import app.tasker.core.ui.component.RowAction
import app.tasker.core.ui.component.TaskRow

/** Inbox tab (CAP-5, TTL-9): newest first; a banner offers quick triage when it overflows. */
@Composable
fun InboxScreen(
    onOpenTask: (TaskId) -> Unit,
    onOpenTriage: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosingProject by remember { mutableStateOf<Task?>(null) }
    if (state.loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        if (state.overflow) {
            item(key = "triage") {
                Banner(
                    text = pluralStringResource(R.plurals.inbox_overflow, state.tasks.size, state.tasks.size),
                    icon = Icons.Outlined.Rule,
                    actionLabel = stringResource(R.string.inbox_triage_action),
                    onAction = onOpenTriage,
                    modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.s),
                )
            }
        }
        if (state.tasks.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.inbox_empty_title),
                    body = stringResource(R.string.inbox_empty_body),
                    icon = Icons.Outlined.Inbox,
                )
            }
        }
        items(state.tasks, key = { it.id }) { task ->
            TaskRow(
                task = task,
                onClick = { onOpenTask(task.id) },
                onComplete = { viewModel.toggleDone(task) },
                onPostpone = { viewModel.postpone(task, it) },
                actions = listOf(
                    RowAction(stringResource(UiR.string.action_to_today), Icons.Outlined.WbSunny) { viewModel.move(task, Bucket.TODAY) },
                    RowAction(stringResource(UiR.string.action_to_week), Icons.Outlined.DateRange) { viewModel.move(task, Bucket.WEEK) },
                    RowAction(stringResource(UiR.string.action_to_someday), Icons.Outlined.NightsStay) {
                        viewModel.move(task, Bucket.SOMEDAY)
                    },
                    RowAction(stringResource(UiR.string.action_to_project), Icons.Outlined.Folder) { choosingProject = task },
                    RowAction(stringResource(UiR.string.action_archive), Icons.Outlined.Archive) { viewModel.archive(task) },
                ),
                modifier = Modifier.animateItem(),
            )
        }
    }
    choosingProject?.let { task ->
        ProjectPickerDialog(
            projects = state.projects,
            current = task.projectId,
            allowNone = false,
            onDismiss = { choosingProject = null },
            onPick = { id ->
                choosingProject = null
                viewModel.setProject(task, id)
            },
            onCreate = { name ->
                choosingProject = null
                viewModel.moveToNewProject(task, name)
            },
        )
    }
}
