package app.tasker.core.domain.plan

import app.tasker.core.model.Deadline
import app.tasker.core.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Derived deadline state (tech plan §4.2: derived values are never stored).
 * A date-only deadline is overdue when the logical day is after it; a timed deadline when its instant passed.
 * Only deadlines can be overdue; plan dates never are (DAT-1, DAT-2).
 */
object TaskTiming {
    fun isOverdue(deadline: Deadline, now: Instant, today: LocalDate, zone: ZoneId): Boolean =
        deadline.instantOrNull(zone)?.isBefore(now) ?: deadline.date.isBefore(today)

    fun isOverdue(task: Task, now: Instant, today: LocalDate, zone: ZoneId): Boolean {
        val deadline = task.deadline ?: return false
        return task.status.isActive && isOverdue(deadline, now, today, zone)
    }

    /** Deadline not later than today + [horizonDays] (DAT-6, interpretation 6). */
    fun isDeadlineSoon(deadline: Deadline, today: LocalDate, horizonDays: Int): Boolean =
        !deadline.date.isAfter(today.plusDays(horizonDays.toLong()))

    /** Deadline still ahead (TTL-7: such tasks are never auto-archived). */
    fun hasFutureDeadline(task: Task, now: Instant, today: LocalDate, zone: ZoneId): Boolean {
        val deadline = task.deadline ?: return false
        return !isOverdue(deadline, now, today, zone)
    }

    /** Sort key for deadlines: date, then time; date-only deadlines sort as end of day. */
    fun sortKey(deadline: Deadline): LocalDateTime = deadline.date.atTime(deadline.time ?: LocalTime.MAX)
}
