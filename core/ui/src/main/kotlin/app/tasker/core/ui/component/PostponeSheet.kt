package app.tasker.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.domain.postpone.PostponeOption
import app.tasker.core.ui.LocalDayContext
import app.tasker.core.ui.R
import app.tasker.core.ui.format.Formats

/**
 * Postpone menu (EXC-2): tomorrow, this week, someday or a date. The deadline never changes, which the sheet says
 * when the task has one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostponeSheet(
    onDismiss: () -> Unit,
    onSelect: (PostponeOption) -> Unit,
    hasDeadline: Boolean = false,
) {
    val today = LocalDayContext.current.today
    var pickDate by rememberSaveable { mutableStateOf(false) }
    if (pickDate) {
        DateDialog(
            initial = null,
            minDate = today.plusDays(1),
            onDismiss = onDismiss,
            onConfirm = { onSelect(PostponeOption.OnDate(it)) },
        )
        return
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(bottom = TaskerTheme.spacing.l)) {
            Text(
                text = stringResource(R.string.postpone_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(horizontal = TaskerTheme.spacing.l, vertical = TaskerTheme.spacing.s)
                    .semantics { heading() },
            )
            if (hasDeadline) {
                Text(
                    text = stringResource(R.string.postpone_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l),
                )
            }
            PostponeItem(
                title = stringResource(R.string.postpone_tomorrow),
                detail = Formats.weekdayDate(today.plusDays(1)),
                icon = Icons.Outlined.WbSunny,
                onClick = { onSelect(PostponeOption.Tomorrow) },
            )
            PostponeItem(
                title = stringResource(R.string.postpone_week),
                detail = null,
                icon = Icons.Outlined.DateRange,
                onClick = { onSelect(PostponeOption.Week) },
            )
            PostponeItem(
                title = stringResource(R.string.postpone_someday),
                detail = null,
                icon = Icons.Outlined.NightsStay,
                onClick = { onSelect(PostponeOption.Someday) },
            )
            PostponeItem(
                title = stringResource(R.string.postpone_date),
                detail = null,
                icon = Icons.Outlined.CalendarMonth,
                onClick = { pickDate = true },
            )
        }
    }
}

@Composable
private fun PostponeItem(title: String, detail: String?, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
