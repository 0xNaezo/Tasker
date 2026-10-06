package app.tasker.feature.review

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
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.tasker.core.domain.review.ReviewItem
import app.tasker.core.domain.review.ReviewReason
import app.tasker.core.model.ProjectId
import app.tasker.core.model.ReviewDecision
import app.tasker.core.model.TaskId
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.component.EmptyState
import app.tasker.core.ui.component.ReasonLabel
import app.tasker.core.ui.component.TaskChips
import app.tasker.core.ui.format.Reasons

/** Relevance review (§14.2): a card with its reason and three actions; undecided cards are simply left for later. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    onOpenProject: (ProjectId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var addingStep by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.review_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.review_back))
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
                title = stringResource(R.string.review_done_title),
                body = stringResource(R.string.review_done_body),
                icon = Icons.Outlined.TaskAlt,
                actionLabel = stringResource(R.string.review_back),
                onAction = onBack,
                modifier = Modifier.padding(padding),
            )
            else -> {
                LaunchedEffect(current.key) { viewModel.shown(current) }
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(TaskerTheme.spacing.l),
                    verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.l),
                ) {
                    Text(stringResource(R.string.review_progress, state.position, state.total), style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator(
                        progress = { (state.position - 1).toFloat() / state.total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when (current) {
                        is ReviewItem.TaskCard -> TaskCard(current, onOpen = { onOpenTask(current.task.id) }) { decision ->
                            viewModel.decide(current, decision)
                        }
                        is ReviewItem.ProjectCard -> ProjectCard(
                            item = current,
                            onOpen = { onOpenProject(current.project.id) },
                            onDecide = { viewModel.decide(current, it) },
                            onAddStep = { addingStep = current.project.id },
                        )
                    }
                    TextButton(onClick = { viewModel.skip(current) }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.review_later))
                    }
                }
            }
        }
    }
    val stepFor = (state.current as? ReviewItem.ProjectCard)?.takeIf { it.project.id == addingStep }
    if (stepFor != null) {
        StepDialog(
            onDismiss = { addingStep = null },
            onAdd = { text ->
                addingStep = null
                viewModel.addStep(stepFor, text)
            },
        )
    }
}

@Composable
private fun TaskCard(item: ReviewItem.TaskCard, onOpen: () -> Unit, onDecide: (ReviewDecision) -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(TaskerTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s)) {
            Text(item.task.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            TaskChips(task = item.task)
            ReasonLabel(Reasons.review(item.reason), color = TaskerTheme.colors.review)
        }
    }
    Decisions {
        Decision(stringResource(R.string.review_relevant), Icons.Outlined.Check) { onDecide(ReviewDecision.RELEVANT) }
        Decision(stringResource(UiR.string.action_to_someday), Icons.Outlined.NightsStay) { onDecide(ReviewDecision.SOMEDAY) }
        Decision(stringResource(UiR.string.action_archive), Icons.Outlined.Archive) { onDecide(ReviewDecision.ARCHIVE) }
    }
}

@Composable
private fun ProjectCard(item: ReviewItem.ProjectCard, onOpen: () -> Unit, onDecide: (ReviewDecision) -> Unit, onAddStep: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(TaskerTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s)) {
            ReasonLabel(stringResource(R.string.review_project), icon = Icons.Outlined.Folder)
            Text(item.project.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            item.project.outcome?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            ReasonLabel(Reasons.review(item.reason), color = TaskerTheme.colors.review)
        }
    }
    Decisions {
        if (item.reason == ReviewReason.NoNextStep) {
            Decision(stringResource(R.string.review_add_step), Icons.Outlined.AddTask, onAddStep)
        } else {
            Decision(stringResource(R.string.review_relevant), Icons.Outlined.Check) { onDecide(ReviewDecision.RELEVANT) }
        }
        Decision(stringResource(R.string.review_complete_project), Icons.Outlined.TaskAlt) { onDecide(ReviewDecision.COMPLETE_PROJECT) }
        Decision(stringResource(UiR.string.action_archive), Icons.Outlined.Archive) { onDecide(ReviewDecision.ARCHIVE) }
    }
}

@Composable
private fun Decisions(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s),
    ) { content() }
}

@Composable
private fun Decision(label: String, icon: ImageVector, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = TaskerTheme.spacing.s))
        Text(label)
    }
}

@Composable
private fun StepDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_add_step)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.review_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) } },
    )
}
