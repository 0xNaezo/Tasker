package app.tasker.core.domain.capacity

import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AppSettings
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Half-open time interval [start, end). */
data class TimeInterval(val start: Instant, val end: Instant) {
    init {
        require(!end.isBefore(start)) { "Interval end must not precede start" }
    }

    val minutes: Long get() = Duration.between(start, end).toMinutes()

    fun intersect(other: TimeInterval): TimeInterval? {
        val s = maxOf(start, other.start)
        val e = minOf(end, other.end)
        return if (e.isAfter(s)) TimeInterval(s, e) else null
    }
}

object Intervals {
    /** Merges overlapping and touching intervals (tech plan §10.2). */
    fun merge(intervals: Collection<TimeInterval>): List<TimeInterval> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it.start }
        val result = ArrayList<TimeInterval>(sorted.size)
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            current = if (!next.start.isAfter(current.end)) {
                TimeInterval(current.start, maxOf(current.end, next.end))
            } else {
                result += current
                next
            }
        }
        result += current
        return result
    }

    fun totalMinutes(intervals: Collection<TimeInterval>): Long = merge(intervals).sumOf { it.minutes }

    fun clip(intervals: Collection<TimeInterval>, window: TimeInterval): List<TimeInterval> =
        merge(intervals).mapNotNull { it.intersect(window) }
}

/**
 * Day capacity (PLN-2, PLN-3, tech plan §10.1, interpretations 5 and 18):
 * window = working hours of the day (for today only the remaining part), empty on non-working days
 * and after the end of the working day; capacity = (window − busy) × (1 − buffer).
 */
data class Capacity(
    val day: LocalDate,
    val window: TimeInterval?,
    val workingMin: Int,
    val busyMin: Int,
    val freeMin: Int,
    val capacityMin: Int,
    val isWorkDay: Boolean,
    val calendarAvailable: Boolean,
) {
    /** Overload in minutes for the given unfinished planned minutes (PLN-7). */
    fun overloadFor(plannedMin: Int): Int = (plannedMin - capacityMin).coerceAtLeast(0)
}

class CapacityCalculator(private val clock: DayClock) {
    /** Full working window of [day], ignoring "now". Null on non-working days. */
    fun workWindow(day: LocalDate, settings: AppSettings): TimeInterval? {
        if (!settings.isWorkDay(day.dayOfWeek.value)) return null
        val start = clock.at(day, settings.workStartMinutes)
        val endDate = if (settings.workEndMinutes <= settings.workStartMinutes) day.plusDays(1) else day
        val end = clock.at(endDate, settings.workEndMinutes)
        return if (end.isAfter(start)) TimeInterval(start, end) else null
    }

    fun compute(
        day: LocalDate,
        settings: AppSettings,
        busy: Collection<TimeInterval>,
        calendarAvailable: Boolean,
        now: Instant = clock.now(),
    ): Capacity {
        val isWorkDay = settings.isWorkDay(day.dayOfWeek.value)
        val full = workWindow(day, settings)
        val window = when {
            full == null -> null
            day == clock.logicalDay(now) -> {
                val start = maxOf(full.start, now)
                if (start.isBefore(full.end)) TimeInterval(start, full.end) else null
            }
            day.isBefore(clock.logicalDay(now)) -> null
            else -> full
        }
        if (window == null) {
            return Capacity(day, null, 0, 0, 0, 0, isWorkDay, calendarAvailable)
        }
        val busyMin = if (calendarAvailable) Intervals.totalMinutes(Intervals.clip(busy, window)) else 0L
        val workingMin = window.minutes
        val freeMin = (workingMin - busyMin).coerceAtLeast(0)
        val buffer = settings.bufferPercent.coerceIn(0, MAX_PERCENT)
        val capacity = freeMin * (MAX_PERCENT - buffer) / MAX_PERCENT
        return Capacity(
            day = day,
            window = window,
            workingMin = workingMin.toInt(),
            busyMin = busyMin.toInt(),
            freeMin = freeMin.toInt(),
            capacityMin = capacity.toInt(),
            isWorkDay = isWorkDay,
            calendarAvailable = calendarAvailable,
        )
    }

    private companion object {
        const val MAX_PERCENT = 100
    }
}
