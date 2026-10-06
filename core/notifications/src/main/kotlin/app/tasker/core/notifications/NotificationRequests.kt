package app.tasker.core.notifications

import android.content.Context
import android.text.format.DateFormat
import app.tasker.core.domain.notify.NotificationType
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.Deadline
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Texts of the notifications the app sends, in the language of the app (ru, uk, en). */
@Singleton
class NotificationRequests @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val clock: DayClock,
) {
    /**
     * Morning plan (tech plan §10.5, TTL-5): "Plan is ready: 5 tasks, 4 h of 5 h free, and 3 tasks to review" —
     * the same numbers as the plan screen, because both come from the stored draft.
     */
    fun planReady(day: LocalDate, taskCount: Int, plannedMinutes: Int, freeMinutes: Int, reviewCount: Int): NotificationRequest {
        val resources = context.resources
        val tasks = resources.getQuantityString(R.plurals.notification_plan_tasks, taskCount, taskCount)
        val text = buildString {
            append(context.getString(R.string.notification_plan_text, tasks, duration(plannedMinutes), duration(freeMinutes)))
            if (reviewCount > 0) append(resources.getQuantityString(R.plurals.notification_plan_review, reviewCount, reviewCount))
        }
        return NotificationRequest(NotificationType.PLAN_READY, planKey(day), context.getString(R.string.notification_plan_title), text)
    }

    /**
     * Deadline reminder (NTF-2): the task title and when the deadline is; [key] identifies the reminder slot. "Today"
     * and "tomorrow" are counted from [deliverAt], when the reminder is expected to be shown: a reminder held back by
     * quiet hours must not say "tomorrow" on the morning of the deadline.
     */
    fun deadline(taskId: String, title: String, deadline: Deadline, key: String, deliverAt: Instant = clock.now()): NotificationRequest =
        NotificationRequest(
            type = NotificationType.DEADLINE,
            key = key,
            title = title,
            text = deadlineText(deadline, deliverAt),
            taskId = taskId,
            deliverBy = DeadlineTimes.end(deadline, clock),
        )

    /** "45 min", "4 h", "4 h 54 min". */
    fun duration(minutes: Int): String {
        val total = minutes.coerceAtLeast(0)
        val hours = total / MINUTES_PER_HOUR
        val rest = total % MINUTES_PER_HOUR
        return when {
            hours == 0 -> context.getString(R.string.notification_duration_minutes, rest)
            rest == 0 -> context.getString(R.string.notification_duration_hours, hours)
            else -> context.getString(R.string.notification_duration_hours_minutes, hours, rest)
        }
    }

    private fun deadlineText(deadline: Deadline, deliverAt: Instant): String {
        val zone = clock.zone()
        val at = deadline.instantOrNull(zone)?.atZone(zone)
        val date = at?.toLocalDate() ?: deadline.date
        val time = at?.let { formatter(if (DateFormat.is24HourFormat(context)) "Hm" else "hm", "HH:mm").format(it) }
        val today = clock.local(deliverAt).toLocalDate()
        return when (date) {
            today -> time?.let { context.getString(R.string.notification_deadline_today_at, it) }
                ?: context.getString(R.string.notification_deadline_today)
            today.plusDays(1) -> time?.let { context.getString(R.string.notification_deadline_tomorrow_at, it) }
                ?: context.getString(R.string.notification_deadline_tomorrow)
            else -> {
                val day = formatter("dMMM", "d MMM").format(date)
                time?.let { context.getString(R.string.notification_deadline_on_at, day, it) }
                    ?: context.getString(R.string.notification_deadline_on, day)
            }
        }
    }

    /** A formatter for an ICU skeleton in the app language; [fallback] covers patterns java.time cannot read. */
    private fun formatter(skeleton: String, fallback: String): DateTimeFormatter {
        val locale: Locale = context.resources.configuration.locales[0]
        return try {
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        } catch (ignored: IllegalArgumentException) {
            DateTimeFormatter.ofPattern(fallback, locale)
        }
    }

    companion object {
        private const val MINUTES_PER_HOUR = 60

        /** One morning plan notification per logical day. */
        fun planKey(day: LocalDate): String = "plan|$day"
    }
}

/** Deadline instants shared by reminders and the gate. */
object DeadlineTimes {
    /** The moment a deadline passes: its instant, or the local midnight after the date of a date-only deadline. */
    fun end(deadline: Deadline, clock: DayClock): Instant = deadline.instantOrNull(clock.zone()) ?: clock.at(deadline.date.plusDays(1), 0)
}
