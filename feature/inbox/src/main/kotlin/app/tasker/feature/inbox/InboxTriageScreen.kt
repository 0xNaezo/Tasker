package app.tasker.feature.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.model.ReviewDecision
import app.tasker.core.model.Task
import app.tasker.core.model.TaskId
import app.tasker.core.ui.LocalDayContext
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.component.EmptyState
import app.tasker.core.ui.component.ProjectPickerDialog
import app.tasker.core.ui.component.ReasonLabel
import app.tasker.core.ui.component.TaskChips
import app.tasker.core.ui.format.Formats

/** Quick triage (TTL-9): one Inbox task per card with one-tap movements. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxTriageScreen(
    onBack: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InboxTriageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosingProject by remember { mutableStateOf<Task?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox_triage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.inbox_back))
                    }
                },
            )
        },
    ) { padding ->
        val current = state.current
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            current == null -> EmptyState(
                title = stringResource(R.string.inbox_triage_done),
                icon = Icons.Outlined.TaskAlt,
                actionLabel = stringResource(R.string.inbox_back),
                onAction = onBack,
                modifier = Modifier.padding(padding),
            )
            else -> {
                LaunchedEffect(current.id) { viewModel.shown(current) }
                TriageCard(
                    task = current,
                    position = state.position,
                    total = state.total,
                    onOpen = { onOpenTask(current.id) },
                    onDecide = { viewModel.decide(current, it) },
                    onProject = { choosingProject = current },
                    onSkip = { viewModel.skip(current) },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
    choosingProject?.let { task ->
        ProjectPickerDialog(
            projects = state.projects,
            current = null,
            allowNone = false,
            onDismiss = { choosingProject = null },
            onPick = { id ->
                choosingProject = null
                if (id != null) viewModel.decide(task, ReviewDecision.PROJECT, id)
            },
            onCreate = { name ->
                choosingProject = null
                viewModel.newProject(task, name)
            },
        )
    }
}

@Composable
private fun TriageCard(
    task: Task,
    position: Int,
    total: Int,
    onOpen: () -> Unit,
    onDecide: (ReviewDecision) -> Unit,
    onProject: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(TaskerTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.l),
    ) {
        Text(stringResource(R.string.inbox_triage_progress, position, total), style = MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(progress = { (position - 1).toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
        Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(TaskerTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s)) {
                Text(task.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                TaskChips(task = task)
                val created = LocalDayContext.current.let { task.createdAt.atZone(it.zone).toLocalDate() }
                ReasonLabel(stringResource(R.string.inbox_added_on, Formats.day(created)))
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s),
            verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s),
        ) {
            Decision(stringResource(UiR.string.action_to_today), Icons.Outlined.WbSunny) { onDecide(ReviewDecision.TODAY) }
            Decision(stringResource(UiR.string.action_to_week), Icons.Outlined.DateRange) { onDecide(ReviewDecision.WEEK) }
            Decision(stringResource(UiR.string.action_to_someday), Icons.Outlined.NightsStay) { onDecide(ReviewDecision.SOMEDAY) }
            Decision(stringResource(UiR.string.action_to_project), Icons.Outlined.Folder, onProject)
            Decision(stringResource(UiR.string.action_archive), Icons.Outlined.Archive) { onDecide(ReviewDecision.ARCHIVE) }
        }
        TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.inbox_skip)) }
    }
}

@Composable
private fun Decision(label: String, icon: ImageVector, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = TaskerTheme.spacing.s))
        Text(label)
    }
}
