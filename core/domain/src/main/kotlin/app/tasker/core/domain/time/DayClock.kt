package app.tasker.core.domain.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Injectable time (tech plan §4.2): domain code never reads the system clock directly. */
interface TimeSource {
    fun now(): Instant

    fun zone(): ZoneId
}

/**
 * Single source of "today" (tech plan §7.8, interpretation 9). The logical day starts at the day
 * boundary (04:00 by default), so work after midnight belongs to the previous day. The boundary is
 * a setting and is updated when settings change.
 */
class DayClock(
    private val source: TimeSource,
    boundaryMinutes: Int = DEFAULT_BOUNDARY_MINUTES,
) {
    @Volatile
    var boundaryMinutes: Int = boundaryMinutes
        set(value) {
            require(value in 0 until MINUTES_PER_DAY) { "Day boundary must be within a day" }
            field = value
        }

    fun now(): Instant = source.now()

    fun zone(): ZoneId = source.zone()

    fun localNow(): LocalDateTime = LocalDateTime.ofInstant(now(), zone())

    fun today(): LocalDate = logicalDay(now())

    /** Logical day an instant belongs to. */
    fun logicalDay(instant: Instant): LocalDate =
        LocalDateTime.ofInstant(instant, zone()).minusMinutes(boundaryMinutes.toLong()).toLocalDate()

    /** First instant of a logical day (its boundary on the calendar date). */
    fun dayStart(day: LocalDate): Instant = at(day, boundaryMinutes)

    /** First instant of the next logical day. */
    fun dayEnd(day: LocalDate): Instant = dayStart(day.plusDays(1))

    /** Wall-clock time [minutesOfDay] on a calendar date in the current zone; DST gaps move forward. */
    fun at(date: LocalDate, minutesOfDay: Int): Instant =
        date.atStartOfDay().plusMinutes(minutesOfDay.toLong()).atZone(zone()).toInstant()

    /** Local wall-clock date-time of an instant in the current zone. */
    fun local(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, zone())

    /** Minutes since local midnight of an instant. */
    fun minutesOfDay(instant: Instant): Int = local(instant).toLocalTime().toSecondOfDay() / SECONDS_PER_MINUTE

    /** Whole logical days between the day of [instant] and [day]; negative if [instant] is later. */
    fun daysSince(instant: Instant, day: LocalDate = today()): Long = ChronoUnit.DAYS.between(logicalDay(instant), day)

    companion object {
        const val DEFAULT_BOUNDARY_MINUTES = 4 * 60
        const val MINUTES_PER_DAY = 24 * 60
        private const val SECONDS_PER_MINUTE = 60
    }
}
