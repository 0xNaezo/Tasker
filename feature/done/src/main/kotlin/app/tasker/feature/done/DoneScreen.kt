package app.tasker.feature.done

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tasker.core.data.repository.DoneDay
import app.tasker.core.data.repository.DoneWeek
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.model.ProjectId
import app.tasker.core.model.Task
import app.tasker.core.model.TaskId
import app.tasker.core.ui.LocalDayContext
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.component.EmptyState
import app.tasker.core.ui.component.SectionHeader
import app.tasker.core.ui.component.TaskRow
import app.tasker.core.ui.format.Formats
import java.time.LocalDate

/**
 * "Done" (§14.2, LOG-1, LOG-2, EXC-7): completed tasks by logical day and week, latest first, with the summary of every
 * day and week — done, of which planned and off-plan, and postponed. No streaks, scores or comparisons (principle 6).
 * The check mark of a row marks the task as not done (with Undo); tapping the row opens the task card.
 * The tab has no top bar of its own: the app shell provides it.
 */
@Composable
fun DoneScreen(
    onOpenTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: DoneViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DoneContent(
        state = state,
        onOpenTask = onOpenTask,
        onReopen = viewModel::reopen,
        onShowEarlier = viewModel::showEarlier,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}

/** Stateless body of [DoneScreen]. */
@Composable
internal fun DoneContent(
    state: DoneUiState,
    onOpenTask: (TaskId) -> Unit,
    onReopen: (Task) -> Unit,
    onShowEarlier: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val weeks = state.weeks
    if (weeks == null) {
        Box(modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val today = LocalDayContext.current.today
    val rows = remember(weeks) { DoneLog.rows(weeks) }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        if (rows.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                EmptyState(
                    title = stringResource(R.string.done_empty_title),
                    body = stringResource(R.string.done_empty_body),
                    icon = Icons.Outlined.TaskAlt,
                )
            }
        }
        items(rows, key = { it.key }, contentType = { it.contentType }) { row ->
            when (row) {
                is DoneRow.WeekHeader -> WeekHeader(row.week, today)
                is DoneRow.DayHeader -> DayHeader(row.day, today)
                is DoneRow.Item -> DoneTaskRow(row.task, state.projectNames, onOpenTask, onReopen)
            }
        }
        item(key = "earlier", contentType = "earlier") {
            Box(Modifier.fillMaxWidth().padding(TaskerTheme.spacing.s), contentAlignment = Alignment.Center) {
                TextButton(onClick = onShowEarlier) {
                    Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.padding(end = TaskerTheme.spacing.s))
                    Text(stringResource(R.string.done_show_earlier))
                }
            }
        }
    }
}

@Composable
private fun WeekHeader(week: DoneWeek, today: LocalDate) {
    val relative = WeekLabels.relative(week.weekStart, today)
    val range = WeekLabels.range(week.weekStart, Formats.locale(), today.year)
    val summary = summaryText(SummaryNumbers.of(week))
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = TaskerTheme.spacing.l, end = TaskerTheme.spacing.l, top = TaskerTheme.spacing.l),
    ) {
        Column(
            modifier = Modifier.padding(TaskerTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.xs),
        ) {
            Text(
                text = when (relative) {
                    WeekLabels.Relative.THIS_WEEK -> stringResource(R.string.done_this_week)
                    WeekLabels.Relative.LAST_WEEK -> stringResource(R.string.done_last_week)
                    null -> range
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (relative != null) {
                Text(range, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DayHeader(day: DoneDay, today: LocalDate) {
    Column(Modifier.fillMaxWidth().padding(bottom = TaskerTheme.spacing.xs)) {
        SectionHeader(dayTitle(day.day, today))
        val summary = summaryText(SummaryNumbers.of(day))
        if (summary.isNotEmpty()) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.xs),
            )
        }
    }
}

@Composable
private fun DoneTaskRow(task: Task, projectNames: Map<ProjectId, String>, onOpenTask: (TaskId) -> Unit, onReopen: (Task) -> Unit) {
    TaskRow(
        task = task,
        onClick = { onOpenTask(task.id) },
        // The check mark of a done task reads "Mark as not done" (EXC-7).
        onComplete = { onReopen(task) },
        onPostpone = {},
        projectName = task.projectId?.let { projectNames[it] },
        reason = if (task.offPlan) stringResource(R.string.done_added_as_done) else null,
    )
}

/** "Done: 5 (planned 3, off plan 2) · Postponed: 1"; parts with nothing to report are left out. */
@Composable
private fun summaryText(numbers: SummaryNumbers): String = buildList {
    if (numbers.showsDone) add(stringResource(R.string.done_summary_done, numbers.done, numbers.planned, numbers.offPlan))
    if (numbers.showsPostponed) add(stringResource(R.string.done_summary_postponed, numbers.postponed))
}.joinToString(SEPARATOR)

/** "Today", "Yesterday", otherwise "Sunday, 4 October" (the week header carries the year when it differs). */
@Composable
private fun dayTitle(day: LocalDate, today: LocalDate): String {
    val locale = Formats.locale()
    return when (day) {
        today -> stringResource(UiR.string.day_today).replaceFirstChar { it.titlecase(locale) }
        today.minusDays(1) -> stringResource(UiR.string.day_yesterday).replaceFirstChar { it.titlecase(locale) }
        else -> Formats.fullDate(day)
    }
}

private const val SEPARATOR = " · "
