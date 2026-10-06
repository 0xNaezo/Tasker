package app.tasker.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.model.Task
import app.tasker.core.model.TaskStatus
import app.tasker.core.ui.component.MetaChip
import app.tasker.core.ui.format.Formats

/**
 * Whether a task has a place to show: its bucket, or the Inbox when it has neither a bucket nor a project (the rule of
 * the horizon filter). Project tasks without a bucket have none: their project chip already says where they are.
 */
internal fun hasPlace(task: Task): Boolean = task.bucket != null || task.projectId == null

/**
 * Status and place of a search result or an archived task, in words rather than colour: "Done", "Archived" and the
 * bucket — for an archived task the bucket it returns to on restore (TTL-8). Nothing is composed when there is nothing
 * to say.
 */
@Composable
internal fun PlaceChips(task: Task, modifier: Modifier = Modifier, showStatus: Boolean = true) {
    val status = task.status.takeIf { showStatus && (it == TaskStatus.DONE || it == TaskStatus.ARCHIVED) }
    val place = hasPlace(task) && task.status != TaskStatus.DONE
    if (status == null && !place) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.xs),
    ) {
        when (status) {
            TaskStatus.DONE -> MetaChip(Formats.status(status), icon = Icons.Outlined.TaskAlt)
            TaskStatus.ARCHIVED -> MetaChip(Formats.status(status), icon = Icons.Outlined.Inventory2)
            else -> Unit
        }
        if (place) {
            val bucket = Formats.bucket(task.bucket)
            MetaChip(
                text = bucket,
                icon = if (task.bucket == null) Icons.Outlined.Inbox else Icons.Outlined.ViewAgenda,
                contentDescription = if (task.status == TaskStatus.ARCHIVED) {
                    stringResource(R.string.archive_returns_to, bucket)
                } else {
                    stringResource(R.string.search_place, bucket)
                },
            )
        }
    }
}
