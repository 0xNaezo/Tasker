package app.tasker.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.domain.plan.TaskTiming
import app.tasker.core.model.FieldSource
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import app.tasker.core.ui.LocalDayContext
import app.tasker.core.ui.R
import app.tasker.core.ui.format.Formats

/** Colour role of a [MetaChip]. Only [OVERDUE] is red (DAT-1, §14.4). */
enum class ChipTone { NORMAL, OVERDUE, REVIEW, AI }

/** Small non-interactive label: deadline, plan date, size, project, marks. */
@Composable
fun MetaChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: ChipTone = ChipTone.NORMAL,
    contentDescription: String? = null,
) {
    val colors = TaskerTheme.colors
    val container = when (tone) {
        ChipTone.NORMAL -> MaterialTheme.colorScheme.surfaceContainerHigh
        ChipTone.OVERDUE -> colors.overdueContainer
        ChipTone.REVIEW -> colors.reviewContainer
        ChipTone.AI -> colors.aiContainer
    }
    val content = when (tone) {
        ChipTone.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
        ChipTone.OVERDUE -> colors.overdue
        ChipTone.REVIEW -> colors.review
        ChipTone.AI -> colors.ai
    }
    val description = contentDescription ?: text
    Surface(
        modifier = modifier.clearAndSetSemantics { this.contentDescription = description },
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Compact chips of a task row (§14.3): status for started tasks, deadline (red only when overdue), plan date, size,
 * project, tags, "in review" and the AI mark (AI-4).
 */
@Composable
fun TaskChips(
    task: Task,
    modifier: Modifier = Modifier,
    projectName: String? = null,
    showPlanDate: Boolean = true,
    showTags: Boolean = true,
) {
    val context = LocalDayContext.current
    val overdue = TaskTiming.isOverdue(task, context.now, context.today, context.zone)
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.xs),
    ) {
        when (task.status) {
            TaskStatus.IN_PROGRESS -> MetaChip(Formats.status(task.status), icon = Icons.Outlined.PlayArrow)
            TaskStatus.PAUSED -> MetaChip(Formats.status(task.status), icon = Icons.Outlined.Pause)
            else -> Unit
        }
        task.deadline?.let { deadline ->
            val text = Formats.deadline(deadline)
            MetaChip(
                text = text,
                icon = Icons.Outlined.Flag,
                tone = if (overdue) ChipTone.OVERDUE else ChipTone.NORMAL,
                contentDescription = stringResource(if (overdue) R.string.cd_deadline_overdue else R.string.cd_deadline, text),
            )
        }
        val planDate = task.planDate
        if (showPlanDate && planDate != null) {
            val text = Formats.day(planDate)
            MetaChip(text, icon = Icons.Outlined.Event, contentDescription = stringResource(R.string.cd_plan_date, text))
        }
        task.estimate?.let { estimate ->
            val text = Formats.estimate(estimate)
            MetaChip(text, icon = Icons.Outlined.Timer, contentDescription = stringResource(R.string.cd_estimate, text))
        }
        if (projectName != null) {
            MetaChip(projectName, icon = Icons.Outlined.Folder, contentDescription = stringResource(R.string.cd_project, projectName))
        }
        if (showTags) task.tags.forEach { tag -> MetaChip("@$tag") }
        if (task.inReview) MetaChip(stringResource(R.string.chip_in_review), tone = ChipTone.REVIEW)
        if (task.fieldSources.containsValue(FieldSource.AI)) {
            MetaChip(
                text = stringResource(R.string.chip_ai),
                icon = Icons.Outlined.AutoAwesome,
                tone = ChipTone.AI,
                contentDescription = stringResource(R.string.cd_ai_fields),
            )
        }
    }
}
