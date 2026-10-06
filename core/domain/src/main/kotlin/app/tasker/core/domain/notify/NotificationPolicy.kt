package app.tasker.core.domain.notify

import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AppSettings
import app.tasker.core.model.Deadline
import java.time.Instant
import java.time.LocalDate

/**
 * Closed list of notification types (NTF-1, NTF-6). There is deliberately no "you didn't do it" type;
 * a test pins this list.
 */
enum class NotificationType(val countsInBudget: Boolean, val allowedInQuietHours: Boolean) {
    /** Morning plan (PLN-4, NTF-1). Comes at the chosen time even inside quiet hours (interpretation 17). */
    PLAN_READY(countsInBudget = true, allowedInQuietHours = true),

    /** Deadline reminders do not count towards the daily limit (NTF-3). */
    DEADLINE(countsInBudget = false, allowedInQuietHours = false),

    /** Weekly review (next version, REV-1). */
    WEEKLY_REVIEW(countsInBudget = true, allowedInQuietHours = false),

    /** Integrations (next version), off by default. */
    INTEGRATION(countsInBudget = true, allowedInQuietHours = false),

    /** One grouped delivery of everything deferred by quiet hours; counts as a single notification (NTF-4). */
    QUIET_HOURS_DIGEST(countsInBudget = true, allowedInQuietHours = false),
}

/** Quiet hours as minutes of day; the interval may wrap over midnight (22:00–08:00). */
data class QuietHours(val startMinutes: Int, val endMinutes: Int) {
    val isEnabled: Boolean get() = startMinutes != endMinutes

    fun contains(minutesOfDay: Int): Boolean = when {
        !isEnabled -> false
        startMinutes < endMinutes -> minutesOfDay in startMinutes until endMinutes
        else -> minutesOfDay >= startMinutes || minutesOfDay < endMinutes
    }

    fun contains(instant: Instant, clock: DayClock): Boolean = contains(clock.minutesOfDay(instant))

    /** First instant at or after [instant] outside quiet hours. */
    fun endAfter(instant: Instant, clock: DayClock): Instant {
        if (!contains(instant, clock)) return instant
        val local = clock.local(instant)
        val date = if (clock.minutesOfDay(instant) >= endMinutes && startMinutes > endMinutes) {
            local.toLocalDate().plusDays(1)
        } else {
            local.toLocalDate()
        }
        return clock.at(date, endMinutes)
    }

    /** Start of the quiet period that contains [instant] (or the next one). */
    fun startBefore(instant: Instant, clock: DayClock): Instant {
        val local = clock.local(instant)
        val minutes = clock.minutesOfDay(instant)
        val date = if (startMinutes > endMinutes && minutes < endMinutes) local.toLocalDate().minusDays(1) else local.toLocalDate()
        return clock.at(date, startMinutes)
    }

    companion object {
        fun of(settings: AppSettings) = QuietHours(settings.quietStartMinutes, settings.quietEndMinutes)
    }
}

sealed interface GateDecision {
    data object Send : GateDecision

    data class Defer(val until: Instant) : GateDecision

    /** Over the daily limit: nothing is sent, the information stays in the app (NTF-3). */
    data object OverBudget : GateDecision

    data object Disabled : GateDecision
}

/** NotificationGate rules (tech plan §12.1): type enabled → quiet hours → daily budget. */
object NotificationPolicy {
    fun isEnabled(type: NotificationType, settings: AppSettings): Boolean = when (type) {
        NotificationType.PLAN_READY -> settings.notifyPlan
        NotificationType.DEADLINE -> settings.notifyDeadlines
        NotificationType.WEEKLY_REVIEW -> settings.notifyWeeklyReview
        NotificationType.INTEGRATION -> false
        NotificationType.QUIET_HOURS_DIGEST -> true
    }

    fun decide(
        type: NotificationType,
        at: Instant,
        settings: AppSettings,
        sentTodayInBudget: Int,
        clock: DayClock,
    ): GateDecision {
        if (!isEnabled(type, settings)) return GateDecision.Disabled
        val quiet = QuietHours.of(settings)
        if (!type.allowedInQuietHours && quiet.contains(at, clock)) {
            return GateDecision.Defer(quiet.endAfter(at, clock))
        }
        if (type.countsInBudget && sentTodayInBudget >= settings.dailyNotificationLimit) return GateDecision.OverBudget
        return GateDecision.Send
    }
}

/** One planned deadline reminder; [key] identifies it across reschedules so it is sent only once. */
data class ReminderSlot(val taskId: String, val at: Instant, val key: String)

/**
 * Deadline reminders (NTF-2, tech plan §12.2): a date-only deadline is reminded the day before at the morning plan
 * time; a timed deadline 24 h and 2 h before. Offsets are global or per task. If a reminder falls into quiet hours
 * and would therefore arrive after the deadline itself, it is moved to just before the quiet hours (interpretation 17).
 */
object ReminderPlanner {
    fun slots(
        taskId: String,
        deadline: Deadline,
        offsetsMinutes: List<Int>,
        settings: AppSettings,
        clock: DayClock,
    ): List<ReminderSlot> {
        val quiet = QuietHours.of(settings)
        val deadlineInstant = deadline.instantOrNull(clock.zone())
        val effectiveDeadline = deadlineInstant ?: clock.at(deadline.date.plusDays(1), 0)
        return offsetsMinutes.distinct().sortedDescending().mapNotNull { offset ->
            val raw = if (deadlineInstant != null) {
                deadlineInstant.minusSeconds(offset * SECONDS_PER_MINUTE)
            } else {
                if (offset < MINUTES_PER_DAY) return@mapNotNull null
                val days = offset / MINUTES_PER_DAY
                clock.at(deadline.date.minusDays(days.toLong()), settings.effectivePlanTimeMinutes)
            }
            val adjusted = if (quiet.contains(raw, clock) && !quiet.endAfter(raw, clock).isBefore(effectiveDeadline)) {
                quiet.startBefore(raw, clock).minusSeconds(SECONDS_PER_MINUTE)
            } else {
                raw
            }
            ReminderSlot(taskId, adjusted, "$taskId|$offset|${deadline.date}|${deadline.time}")
        }
    }

    /** Next morning plan notification time (working days only, PLN-4, interpretation 18). */
    fun nextPlanTime(after: Instant, settings: AppSettings, clock: DayClock): Instant? {
        val start: LocalDate = clock.local(after).toLocalDate()
        for (offset in 0..DAYS_TO_SEARCH) {
            val date = start.plusDays(offset.toLong())
            if (!settings.isWorkDay(date.dayOfWeek.value)) continue
            val at = clock.at(date, settings.effectivePlanTimeMinutes)
            if (at.isAfter(after)) return at
        }
        return null
    }

    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_DAY = 24 * 60
    private const val DAYS_TO_SEARCH = 14
}
