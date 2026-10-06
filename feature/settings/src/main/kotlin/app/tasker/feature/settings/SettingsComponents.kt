package app.tasker.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.tasker.core.designsystem.theme.TaskerTheme
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.format.Formats
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.WeekFields

/** A setting with its current value; tap opens the editor. */
@Composable
internal fun ValueRow(title: String, value: String, onClick: () -> Unit, icon: ImageVector? = null, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

/** Work days as chips in the locale's week order (SET-1). */
@Composable
internal fun DaysRow(selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val locale = Formats.locale()
    val first = WeekFields.of(locale).firstDayOfWeek
    FlowRow(
        modifier = Modifier.padding(horizontal = TaskerTheme.spacing.l),
        horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.xs),
    ) {
        for (offset in 0L until DAYS_PER_WEEK) {
            val day = first.plus(offset)
            val on = day.value in selected
            val name = day.getDisplayName(TextStyle.FULL_STANDALONE, locale)
            FilterChip(
                selected = on,
                onClick = { onChange(if (on) selected - day.value else selected + day.value) },
                label = { Text(day.getDisplayName(TextStyle.SHORT_STANDALONE, locale)) },
                // The chip says "Mon"; TalkBack reads the full name, and the chip itself reports selection.
                modifier = Modifier.semantics { contentDescription = name },
            )
        }
    }
}

/** A whole number within [range], changed with − and + buttons. */
@Composable
internal fun NumberDialog(
    title: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    format: @Composable (Int) -> String = { it.toString() },
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var current by rememberSaveable { mutableIntStateOf(value.coerceIn(range)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TaskerTheme.spacing.l)) {
                IconButton(onClick = { current = (current - step).coerceIn(range) }, enabled = current > range.first) {
                    Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.settings_less))
                }
                Text(format(current), style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = { current = (current + step).coerceIn(range) }, enabled = current < range.last) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.settings_more))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(current) }) { Text(stringResource(UiR.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) } },
    )
}

/** Several options at once (deadline reminders, parser languages). */
@Composable
internal fun <T> MultiChoiceDialog(
    title: String,
    options: List<Pair<String, T>>,
    selected: Set<T>,
    onDismiss: () -> Unit,
    onSave: (Set<T>) -> Unit,
) {
    var chosen by rememberSaveable { mutableStateOf(options.indices.filter { options[it].second in selected }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, (label, _) ->
                    val checked = index in chosen
                    ListItem(
                        headlineContent = { Text(label) },
                        leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked, role = Role.Checkbox) { on ->
                                chosen = if (on) chosen + index else chosen - index
                            },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(chosen.map { options[it].second }.toSet()) }) { Text(stringResource(UiR.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) } },
    )
}

@Composable
internal fun timeText(minutesOfDay: Int): String = Formats.time(
    LocalTime.of(
        minutesOfDay / MINUTES_PER_HOUR % HOURS_PER_DAY,
        minutesOfDay % MINUTES_PER_HOUR,
    ),
)

@Composable
internal fun dayName(isoDay: Int): String = DayOfWeek.of(isoDay).getDisplayName(TextStyle.FULL_STANDALONE, Formats.locale())
    .replaceFirstChar { it.titlecase(Formats.locale()) }

internal fun LocalTime.minutesOfDay(): Int = hour * MINUTES_PER_HOUR + minute

internal fun Int.toLocalTime(): LocalTime = LocalTime.of(this / MINUTES_PER_HOUR % HOURS_PER_DAY, this % MINUTES_PER_HOUR)

private const val DAYS_PER_WEEK = 7L
private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24

/** One option from a list (review day, app language). */
@Composable
internal fun <T> SingleChoiceDialog(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (label, value) ->
                    ListItem(
                        headlineContent = { Text(label) },
                        leadingContent = { androidx.compose.material3.RadioButton(selected = value == selected, onClick = null) },
                        modifier = Modifier.clickable(role = Role.RadioButton) { onPick(value) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) } },
    )
}
