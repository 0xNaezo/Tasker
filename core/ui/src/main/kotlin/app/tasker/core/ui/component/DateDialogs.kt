package app.tasker.core.ui.component

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.tasker.core.ui.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Date picker for plan dates, deadlines and "postpone to a date". Dates before [minDate] cannot be chosen.
 * [onClear] adds a "Clear" button that removes the date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateDialog(
    initial: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
    minDate: LocalDate? = null,
    onClear: (() -> Unit)? = null,
) {
    val selectable = remember(minDate) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                minDate == null || !utcTimeMillis.toUtcDate().isBefore(minDate)

            override fun isSelectableYear(year: Int): Boolean = minDate == null || year >= minDate.year
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.toUtcMillis(),
        selectableDates = selectable,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onConfirm(it.toUtcDate()) } },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            Row {
                if (onClear != null) TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    ) {
        DatePicker(state = state)
    }
}

/** Time picker for timed deadlines; follows the system 12/24-hour setting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(
    initial: LocalTime?,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val start = initial ?: DEFAULT_TIME
    val state = rememberTimePickerState(
        initialHour = start.hour,
        initialMinute = start.minute,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            Row {
                if (onClear != null) TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
        text = { TimePicker(state = state) },
    )
}

private val DEFAULT_TIME: LocalTime = LocalTime.of(18, 0)

/** The Material date picker works with UTC midnight millis. */
private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
