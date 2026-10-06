package app.tasker.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.unit.dp
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.domain.capacity.Capacity
import app.tasker.core.ui.R
import app.tasker.core.ui.format.Formats

/**
 * Capacity scale (§14.3, PLN-6, PLN-7): planned minutes against the day's capacity, the overload or the free time.
 * Overload uses the amber `overload` token, never red.
 */
@Composable
fun CapacityBar(
    plannedMinutes: Int,
    capacity: Capacity,
    modifier: Modifier = Modifier,
) {
    val colors = TaskerTheme.colors
    val overload = capacity.overloadFor(plannedMinutes)
    val planned = Formats.duration(plannedMinutes)
    val total = Formats.duration(capacity.capacityMin)
    val headline = when {
        !capacity.isWorkDay -> stringResource(R.string.capacity_day_off)
        capacity.window == null -> stringResource(R.string.capacity_day_over)
        else -> stringResource(R.string.capacity_planned, planned, total)
    }
    val status = when {
        overload > 0 -> stringResource(R.string.capacity_overload, Formats.duration(overload))
        capacity.capacityMin > plannedMinutes -> stringResource(
            R.string.capacity_free,
            Formats.duration(capacity.capacityMin - plannedMinutes),
        )
        else -> null
    }
    val noCalendar = if (capacity.calendarAvailable) null else stringResource(R.string.capacity_no_calendar)
    val description = listOfNotNull(stringResource(R.string.cd_capacity, planned, total), status, noCalendar).joinToString(". ")
    val fraction = when {
        plannedMinutes <= 0 -> 0f
        capacity.capacityMin <= 0 -> 1f
        else -> (plannedMinutes.toFloat() / capacity.capacityMin).coerceAtMost(1f)
    }
    Column(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        },
        verticalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.s)) {
            Text(headline, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false))
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (overload > 0) colors.overload else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (noCalendar != null) {
                Text(noCalendar, style = MaterialTheme.typography.labelMedium, color = colors.muted)
            }
        }
        Bar(plannedMinutes = plannedMinutes, capacityMinutes = capacity.capacityMin)
    }
}

@Composable
private fun Bar(plannedMinutes: Int, capacityMinutes: Int) {
    val colors = TaskerTheme.colors
    val overload = (plannedMinutes - capacityMinutes).coerceAtLeast(0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.capacityTrack),
    ) {
        if (overload == 0) {
            if (plannedMinutes > 0 && capacityMinutes > 0) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .weight(plannedMinutes.toFloat())
                        .background(colors.capacityFill),
                )
            }
            val free = capacityMinutes - plannedMinutes
            if (free > 0) Box(Modifier.fillMaxHeight().weight(free.toFloat()))
        } else {
            if (capacityMinutes > 0) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .weight(capacityMinutes.toFloat())
                        .background(colors.capacityFill),
                )
            }
            Box(
                Modifier
                    .fillMaxHeight()
                    .weight(overload.toFloat())
                    .background(colors.overload),
            )
        }
    }
}
