package app.tasker.core.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import app.tasker.core.model.Bucket
import app.tasker.core.model.Deadline
import app.tasker.core.model.Estimate
import app.tasker.core.model.TaskStatus
import app.tasker.core.ui.LocalDayContext
import app.tasker.core.ui.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Locale-aware formatting used by every screen; never reads the clock (uses [LocalDayContext]). */
object Formats {
    private const val MINUTES_PER_HOUR = 60
    private const val WEEK_DAYS = 6L

    @Composable
    @ReadOnlyComposable
    fun locale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

    /** "4 h 54 min", "45 min", "3 h". */
    @Composable
    @ReadOnlyComposable
    fun duration(minutes: Int): String {
        val hours = minutes / MINUTES_PER_HOUR
        val rest = minutes % MINUTES_PER_HOUR
        return when {
            hours == 0 -> stringResource(R.string.duration_minutes, rest)
            rest == 0 -> stringResource(R.string.duration_hours, hours)
            else -> stringResource(R.string.duration_hours_minutes, hours, rest)
        }
    }

    /** "today", "tomorrow", "Fri" within a week, otherwise "9 Oct" (with the year if it differs). */
    @Composable
    @ReadOnlyComposable
    fun day(date: LocalDate, today: LocalDate = LocalDayContext.current.today): String {
        val locale = locale()
        val delta = ChronoUnit.DAYS.between(today, date)
        return when {
            delta == 0L -> stringResource(R.string.day_today)
            delta == 1L -> stringResource(R.string.day_tomorrow)
            delta == -1L -> stringResource(R.string.day_yesterday)
            delta in -WEEK_DAYS..WEEK_DAYS -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            date.year == today.year -> date.format(DateTimeFormatter.ofPattern("d MMM", locale))
            else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        }
    }

    /** "Tuesday, 6 October": the heading of a day. */
    @Composable
    @ReadOnlyComposable
    fun fullDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale()))
        .replaceFirstChar { it.titlecase(locale()) }

    /** "Wed, 7 Oct": an exact date with its weekday. */
    @Composable
    @ReadOnlyComposable
    fun weekdayDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEE, d MMM", locale()))

    @Composable
    @ReadOnlyComposable
    fun time(time: LocalTime): String = time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale()))

    /**
     * A deadline in the current zone; a timed deadline created in another zone is shown in local time (§7.8, the
     * original zone is shown by the task card).
     */
    @Composable
    @ReadOnlyComposable
    fun deadline(deadline: Deadline): String {
        val context = LocalDayContext.current
        val instant = deadline.instantOrNull(context.zone)
            ?: return day(deadline.date, context.today)
        val local = ZonedDateTime.ofInstant(instant, context.zone)
        return stringResource(R.string.day_at_time, day(local.toLocalDate(), context.today), time(local.toLocalTime()))
    }

    @Composable
    @ReadOnlyComposable
    fun bucket(bucket: Bucket?): String = stringResource(
        when (bucket) {
            Bucket.TODAY -> R.string.bucket_today
            Bucket.WEEK -> R.string.bucket_week
            Bucket.SOMEDAY -> R.string.bucket_someday
            null -> R.string.bucket_inbox
        },
    )

    @Composable
    @ReadOnlyComposable
    fun status(status: TaskStatus): String = stringResource(
        when (status) {
            TaskStatus.OPEN -> R.string.status_open
            TaskStatus.IN_PROGRESS -> R.string.status_in_progress
            TaskStatus.PAUSED -> R.string.status_paused
            TaskStatus.DONE -> R.string.status_done
            TaskStatus.ARCHIVED -> R.string.status_archived
        },
    )

    @Composable
    @ReadOnlyComposable
    fun estimate(estimate: Estimate): String = stringResource(
        when (estimate) {
            Estimate.S -> R.string.estimate_s
            Estimate.M -> R.string.estimate_m
            Estimate.L -> R.string.estimate_l
        },
    )
}
