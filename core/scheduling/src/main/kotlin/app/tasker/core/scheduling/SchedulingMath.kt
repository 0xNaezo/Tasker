package app.tasker.core.scheduling

import app.tasker.core.domain.notify.QuietHours
import app.tasker.core.domain.notify.ReminderPlanner
import app.tasker.core.domain.notify.ReminderSlot
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AppSettings
import app.tasker.core.model.Task
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** An inexact alarm fires somewhere inside [start, start + length] (tech plan §12.2: no exact alarms). */
data class AlarmWindow(val start: Instant, val length: Duration) {
    val end: Instant get() = start.plus(length)
}

/** Pure timing of the background work: everything that decides *when*, tested without Android. */
object SchedulingMath {
    /** Window of the morning plan and reminder alarms; Android 12+ widens shorter windows to 10 minutes anyway. */
    val ALARM_WINDOW: Duration = Duration.ofMinutes(10)

    /** The quiet-hours group is not urgent. */
    val QUIET_HOURS_WINDOW: Duration = Duration.ofMinutes(15)

    /** The daily catch-up runs this long after the day boundary, so "today" has certainly turned. */
    val AFTER_BOUNDARY: Duration = Duration.ofMinutes(15)

    /** A periodic catch-up starting later than this after the boundary has drifted and is re-aligned. */
    val ALIGNMENT_TOLERANCE: Duration = Duration.ofHours(2)

    /** A morning plan missed by up to this much (phone off, alarm deferred) still comes. */
    val PLAN_GRACE: Duration = Duration.ofHours(2)

    /** Reminder alarms are kept only this far ahead (system limits on alarms); the daily run arms later ones. */
    val REMINDER_HORIZON: Duration = Duration.ofHours(48)
}

/** Daily catch-up timing (tech plan §11.1, §12.3). */
object MaintenanceTiming {
    /** The first run after [now]: shortly after the next day boundary. */
    fun nextRun(now: Instant, clock: DayClock): Instant = clock.dayEnd(clock.logicalDay(now)).plus(SchedulingMath.AFTER_BOUNDARY)

    fun initialDelay(now: Instant, clock: DayClock): Duration = Duration.between(now, nextRun(now, clock))

    /** Whether a run at [runAt] lands right after a day boundary, as the daily catch-up should. */
    fun isAligned(runAt: Instant, clock: DayClock): Boolean {
        val sinceBoundary = Duration.between(clock.dayStart(clock.logicalDay(runAt)), runAt)
        return !sinceBoundary.isNegative && sinceBoundary <= SchedulingMath.AFTER_BOUNDARY.plus(SchedulingMath.ALIGNMENT_TOLERANCE)
    }
}

/** Morning plan alarm (PLN-4, NTF-1, interpretations 17 and 18). */
object MorningPlanTiming {
    /**
     * The next plan time on a working day. A plan time that passed less than [SchedulingMath.PLAN_GRACE] ago still
     * counts when its day comes after the last handled one ([lastHandled]): a phone that was off at 08:30 gets the plan
     * when it boots, and a handled day never gets a second plan. A past time makes the alarm fire at once. With no
     * handled day yet (fresh install) nothing in the past counts as missed.
     */
    fun nextAlarm(now: Instant, settings: AppSettings, clock: DayClock, lastHandled: LocalDate?): Instant? {
        if (lastHandled == null) return ReminderPlanner.nextPlanTime(now, settings, clock)
        val candidate = ReminderPlanner.nextPlanTime(now.minus(SchedulingMath.PLAN_GRACE), settings, clock) ?: return null
        if (clock.logicalDay(candidate).isAfter(lastHandled)) return candidate
        return ReminderPlanner.nextPlanTime(maxOf(candidate, now), settings, clock)
    }
}

/** Reminder slots of a task with an active deadline, and the moment the deadline passes. */
data class TaskReminders(val task: Task, val slots: List<ReminderSlot>, val deadlineEnd: Instant)

/** A reminder slot chosen for a task. */
data class DueReminder(val reminders: TaskReminders, val slot: ReminderSlot)

/** Deadline reminder timing (NTF-2, NTF-6, tech plan §12.2). */
object ReminderTiming {
    /**
     * Reminders to send at [now]: per task the latest slot that is due — an inexact alarm may fire up to
     * [SchedulingMath.ALARM_WINDOW] early — while the deadline is still ahead; an earlier missed slot is superseded by
     * the later one. Nothing is sent after the deadline: there are no "you did not do it" reminders (NTF-6). A slot that
     * was already past when the task's deadline or reminders last changed ([changedAt]) never fires, so setting a
     * deadline for tonight does not trigger yesterday's "a day before" reminder.
     */
    fun due(reminders: List<TaskReminders>, now: Instant, changedAt: Map<String, Instant>): List<DueReminder> {
        val horizon = now.plus(SchedulingMath.ALARM_WINDOW)
        return reminders.mapNotNull { task ->
            if (!now.isBefore(task.deadlineEnd)) return@mapNotNull null
            val slot = task.slots.filter { !it.at.isAfter(horizon) }.maxByOrNull { it.at } ?: return@mapNotNull null
            val since = changedAt[task.task.id]
            if (since != null && !slot.at.isAfter(since)) return@mapNotNull null
            DueReminder(task, slot)
        }
    }

    /** The earliest slot still to come within [SchedulingMath.REMINDER_HORIZON]: one alarm is enough. */
    fun next(reminders: List<TaskReminders>, now: Instant): DueReminder? {
        val from = now.plus(SchedulingMath.ALARM_WINDOW)
        val until = now.plus(SchedulingMath.REMINDER_HORIZON)
        return reminders
            .flatMap { task -> task.slots.map { DueReminder(task, it) } }
            .filter { it.slot.at.isAfter(from) && !it.slot.at.isAfter(until) && it.slot.at.isBefore(it.reminders.deadlineEnd) }
            .minByOrNull { it.slot.at }
    }

    /**
     * Alarm window for a reminder at [at]: normally [at, at + 10 min]. It must close before the deadline and before
     * quiet hours begin, where the reminder would wait until morning (interpretation 17); then the window ends there
     * and opens up to 10 minutes earlier, never before [now].
     */
    fun window(at: Instant, deadlineEnd: Instant, quiet: QuietHours, clock: DayClock, now: Instant): AlarmWindow {
        val length = SchedulingMath.ALARM_WINDOW
        var end = minOf(at.plus(length), deadlineEnd)
        nextQuietStart(at, quiet, clock)?.let { if (end.isAfter(it)) end = it }
        val start = maxOf(now, minOf(at, end.minus(length)))
        return AlarmWindow(start, if (end.isAfter(start)) Duration.between(start, end) else Duration.ZERO)
    }

    /**
     * When a reminder submitted at [now] will be shown: at the end of quiet hours, unless that is not before the
     * deadline, in which case the notification gate sends it at once (interpretation 17).
     */
    fun deliveryTime(now: Instant, deadlineEnd: Instant, quiet: QuietHours, clock: DayClock): Instant {
        val afterQuietHours = quiet.endAfter(now, clock)
        return if (afterQuietHours.isBefore(deadlineEnd)) afterQuietHours else now
    }

    private fun nextQuietStart(after: Instant, quiet: QuietHours, clock: DayClock): Instant? {
        if (!quiet.isEnabled || quiet.contains(after, clock)) return null
        val date = clock.local(after).toLocalDate()
        return listOf(date, date.plusDays(1)).map { clock.at(it, quiet.startMinutes) }.firstOrNull { it.isAfter(after) }
    }
}

/** The settings alarms depend on; when any of them changes, all alarms are re-registered. */
data class SchedulingInputs(
    val planTimeMinutes: Int,
    val workDays: Set<Int>,
    val quietStartMinutes: Int,
    val quietEndMinutes: Int,
    val deadlineReminderMinutes: List<Int>,
    val notifyPlan: Boolean,
    val notifyDeadlines: Boolean,
    val dayBoundaryMinutes: Int,
) {
    companion object {
        fun of(settings: AppSettings) = SchedulingInputs(
            planTimeMinutes = settings.effectivePlanTimeMinutes,
            workDays = settings.workDays,
            quietStartMinutes = settings.quietStartMinutes,
            quietEndMinutes = settings.quietEndMinutes,
            deadlineReminderMinutes = settings.deadlineReminderMinutes,
            notifyPlan = settings.notifyPlan,
            notifyDeadlines = settings.notifyDeadlines,
            dayBoundaryMinutes = settings.dayBoundaryMinutes,
        )
    }
}
