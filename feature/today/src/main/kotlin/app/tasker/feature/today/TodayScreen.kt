package app.tasker.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tasker.core.data.plan.DayView
import app.tasker.core.data.plan.PlanEntry
import app.tasker.core.data.repository.DailyPrompt
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.domain.plan.Candidate
import app.tasker.core.model.CandidateGroup
import app.tasker.core.model.Task
import app.tasker.core.model.TaskId
import app.tasker.core.model.TaskStatus
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.component.Banner
import app.tasker.core.ui.component.CapacityBar
import app.tasker.core.ui.component.EmptyState
import app.tasker.core.ui.component.PauseDialog
import app.tasker.core.ui.component.RowAction
import app.tasker.core.ui.component.SectionHeader
import app.tasker.core.ui.component.TaskRow
import app.tasker.core.ui.component.WipLimitDialog
import app.tasker.core.ui.format.Formats
import app.tasker.core.ui.format.Reasons

/**
 * "Today" (§14.2, PLN-6, PLN-7, PLN-9, DAT-7, TTL-5): capacity and overload, tasks in progress with their last
 * snapshot, the accepted plan or — without one — the candidates of groups 1–4 and "Build the plan".
 */
@Composable
fun TodayScreen(
    onOpenTask: (TaskId) -> Unit,
    onOpenPlan: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenAiSettings: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val wip by viewModel.wipOutcome.collectAsStateWithLifecycle()
    var pausing by remember { mutableStateOf<Task?>(null) }
    val view = state.view
    if (view == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val rows = TodayRows(
        projectNames = state.projectNames,
        onOpenTask = onOpenTask,
        viewModel = viewModel,
        onPause = { pausing = it },
    )
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "header") { TodayHeader(view, onOpenPlan) }
        banners(view, state, BannerActions(onOpenReview, onOpenInbox, onOpenPlan, onOpenAiSettings), viewModel)
        val working = view.work.map { it.task.id }.toSet()
        if (view.work.isNotEmpty()) {
            item(key = "work-header") { SectionHeader(stringResource(R.string.today_in_progress)) }
            items(view.work, key = { "work-${it.task.id}" }) { entry ->
                rows.TaskItem(entry.task, reason = entry.latestSnapshot?.text, inPlan = false)
            }
        }
        if (view.isAccepted) {
            acceptedPlan(view, working, rows)
        } else {
            candidates(view.unplannedCandidates.filter { it.task.id !in working }, rows)
        }
        if (view.work.isEmpty() && view.entries.isEmpty() && view.unplannedCandidates.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.today_empty_title),
                    body = stringResource(R.string.today_empty_body),
                    icon = Icons.Outlined.WbSunny,
                )
            }
        }
    }
    pausing?.let { task ->
        PauseDialog(
            task = task,
            onDismiss = { pausing = null },
            onPause = { note ->
                pausing = null
                viewModel.pause(task, note)
            },
        )
    }
    wip?.let { outcome ->
        WipLimitDialog(
            started = outcome.task,
            inProgress = outcome.inProgress,
            limit = outcome.limit,
            onPause = viewModel::pauseOther,
            onDismiss = viewModel::dismissWip,
        )
    }
}

@Composable
private fun TodayHeader(view: DayView, onOpenPlan: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.m),
        verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s),
    ) {
        Text(
            text = Formats.fullDate(view.day),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        CapacityBar(plannedMinutes = view.plannedMinutes, capacity = view.capacity)
        if (view.inProgressCount > 0) {
            Text(
                text = stringResource(R.string.today_wip, view.inProgressCount, view.settings.wipLimit),
                style = MaterialTheme.typography.labelLarge,
                color = if (view.overWipLimit) TaskerTheme.colors.overload else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (view.isAccepted) {
                TextButton(onClick = onOpenPlan) { Text(stringResource(R.string.today_open_plan)) }
            } else {
                Button(onClick = onOpenPlan) { Text(stringResource(R.string.today_build_plan)) }
            }
        }
    }
}

private class BannerActions(
    val onOpenReview: () -> Unit,
    val onOpenInbox: () -> Unit,
    val onOpenPlan: () -> Unit,
    val onOpenAiSettings: () -> Unit,
)

private fun LazyListScope.banners(view: DayView, state: TodayUiState, actions: BannerActions, viewModel: TodayViewModel) {
    if (view.questions.isNotEmpty()) {
        item(key = "banner-questions") {
            Banner(
                text = pluralStringResource(R.plurals.today_questions_banner, view.questions.size, view.questions.size),
                icon = Icons.Outlined.Rule,
                actionLabel = stringResource(R.string.today_open_plan),
                onAction = actions.onOpenPlan,
                modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.xs),
            )
        }
    }
    if (view.reviewCount > 0 && !state.reviewBannerDismissed) {
        item(key = "banner-review") {
            Banner(
                text = pluralStringResource(R.plurals.today_review_banner, view.reviewCount, view.reviewCount),
                icon = Icons.Outlined.EditNote,
                actionLabel = stringResource(R.string.today_review_action),
                onAction = actions.onOpenReview,
                onDismiss = { viewModel.dismiss(DailyPrompt.REVIEW) },
                modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.xs),
            )
        }
    }
    if (view.inboxOverflow && !state.inboxBannerDismissed) {
        item(key = "banner-inbox") {
            Banner(
                text = pluralStringResource(R.plurals.today_inbox_banner, view.inboxCount, view.inboxCount),
                icon = Icons.Outlined.Inbox,
                actionLabel = stringResource(R.string.today_inbox_action),
                onAction = actions.onOpenInbox,
                onDismiss = { viewModel.dismiss(DailyPrompt.INBOX_TRIAGE) },
                modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.xs),
            )
        }
    }
    if (state.aiOffer) {
        item(key = "banner-ai") {
            Banner(
                text = stringResource(R.string.today_ai_offer),
                icon = Icons.Outlined.AutoAwesome,
                actionLabel = stringResource(R.string.today_ai_offer_action),
                onAction = actions.onOpenAiSettings,
                onDismiss = viewModel::dismissAiOffer,
                modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.xs),
            )
        }
    }
}

private fun LazyListScope.acceptedPlan(view: DayView, working: Set<TaskId>, rows: TodayRows) {
    val entries = view.entries.filter { it.task.id !in working }
    if (entries.isNotEmpty()) {
        item(key = "plan-header") {
            SectionHeader(
                pluralStringResource(
                    R.plurals.today_plan_section,
                    entries.size,
                    entries.size,
                    Formats.duration(view.plannedMinutes),
                ),
            )
        }
        items(entries, key = { "plan-${it.task.id}" }) { entry ->
            rows.TaskItem(entry.task, reason = entryReason(entry), inPlan = true)
        }
    }
    val suggestions = view.suggestions.filter { it.task.id !in working }
    if (suggestions.isNotEmpty()) {
        item(key = "suggestions-header") { SectionHeader(stringResource(R.string.today_suggestions)) }
        items(suggestions, key = { "suggestion-${it.task.id}" }) { candidate ->
            rows.TaskItem(candidate.task, reason = Reasons.candidate(candidate.reason), inPlan = false, offerAdd = true)
        }
    }
}

private fun LazyListScope.candidates(candidates: List<Candidate>, rows: TodayRows) {
    val groups = candidates.groupBy { it.group }
    listOf(
        CandidateGroup.OVERDUE to R.string.today_group_overdue,
        CandidateGroup.DEADLINE_SOON to R.string.today_group_deadline,
        CandidateGroup.IN_PROGRESS to R.string.today_in_progress,
        CandidateGroup.PLANNED to R.string.today_group_planned,
    ).forEach { (group, title) ->
        val items = groups[group].orEmpty()
        if (items.isEmpty()) return@forEach
        item(key = "group-$group") { SectionHeader(stringResource(title)) }
        items(items, key = { "candidate-${it.task.id}" }) { candidate ->
            rows.TaskItem(candidate.task, reason = Reasons.candidate(candidate.reason), inPlan = false)
        }
    }
}

@Composable
private fun entryReason(entry: PlanEntry): String =
    entry.reason?.let { Reasons.candidate(it) } ?: stringResource(UiR.string.reason_added_manually)

/** Row factory with the actions every row of Today shares. */
private class TodayRows(
    private val projectNames: Map<String, String>,
    private val onOpenTask: (TaskId) -> Unit,
    private val viewModel: TodayViewModel,
    private val onPause: (Task) -> Unit,
) {
    @Composable
    fun TaskItem(task: Task, reason: String?, inPlan: Boolean, offerAdd: Boolean = false) {
        val actions = buildList {
            when (task.status) {
                TaskStatus.OPEN -> add(
                    RowAction(stringResource(UiR.string.action_start), Icons.Outlined.PlayArrow) {
                        viewModel.start(task)
                    },
                )
                TaskStatus.PAUSED -> add(
                    RowAction(stringResource(UiR.string.action_resume), Icons.Outlined.PlayArrow) {
                        viewModel.start(task)
                    },
                )
                TaskStatus.IN_PROGRESS -> add(RowAction(stringResource(UiR.string.action_pause), Icons.Outlined.Pause) { onPause(task) })
                else -> Unit
            }
            if (inPlan) {
                add(
                    RowAction(stringResource(UiR.string.action_remove_from_plan), Icons.Outlined.PlaylistRemove) {
                        viewModel.removeFromPlan(task)
                    },
                )
            } else if (task.status.isActive) {
                add(RowAction(stringResource(UiR.string.action_add_to_plan), Icons.Outlined.PlaylistAdd) { viewModel.addToPlan(task) })
            }
        }
        TaskRow(
            task = task,
            onClick = { onOpenTask(task.id) },
            onComplete = { viewModel.toggleDone(task) },
            onPostpone = { viewModel.postpone(task, it) },
            projectName = task.projectId?.let { projectNames[it] },
            reason = reason,
            actions = actions,
            trailing = if (offerAdd) {
                { TextButton(onClick = { viewModel.addToPlan(task) }) { Text(stringResource(R.string.today_add)) } }
            } else {
                null
            },
        )
    }
}
