package app.tasker.core.parser

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** Date arithmetic relative to the logical today (tech plan §9.3, rules 4 and 5). */
internal class CalendarMath(private val today: LocalDate) {
    fun plusDays(days: Int): LocalDate = today.plusDays(days.toLong())

    /** Rule 4: the nearest such weekday, today included. */
    fun nearestWeekday(day: DayOfWeek): LocalDate = today.with(TemporalAdjusters.nextOrSame(day))

    /** "следующий пт" / "next fri": that weekday in the next calendar week. */
    fun weekdayOfNextWeek(day: DayOfWeek): LocalDate = mondayOfNextWeek().with(TemporalAdjusters.nextOrSame(day))

    fun mondayOfNextWeek(): LocalDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1)

    fun plus(count: Int, unit: OffsetUnit): LocalDate = when (unit) {
        OffsetUnit.DAYS -> today.plusDays(count.toLong())
        OffsetUnit.WEEKS -> today.plusWeeks(count.toLong())
        OffsetUnit.MONTHS -> today.plusMonths(count.toLong())
    }

    /** An explicit date; without a year, rule 5: the nearest such date from today on (29 February waits for a leap year). */
    fun resolve(day: Int, month: Int, year: Int?): LocalDate? {
        if (month !in 1..12 || day < 1 || day > Month.of(month).maxLength()) return null
        if (year != null) {
            return if (day <= YearMonth.of(year, month).lengthOfMonth()) LocalDate.of(year, month, day) else null
        }
        for (offset in 0..LEAP_SEARCH_YEARS) {
            val candidateYear = today.year + offset
            if (day > YearMonth.of(candidateYear, month).lengthOfMonth()) continue
            val candidate = LocalDate.of(candidateYear, month, day)
            if (!candidate.isBefore(today)) return candidate
        }
        return null
    }

    private companion object {
        const val LEAP_SEARCH_YEARS = 8
    }
}

/** Day, month and optional year read from one numeric token. */
internal class DateParts(val day: Int, val month: Int, val year: Int?)

/** Hour and minute read from one numeric token; [hasMinutes] is false for a bare number such as "18". */
internal class ClockParts(val hour: Int, val minute: Int, val hasMinutes: Boolean)

/**
 * Numeric dates and times inside a single token: "12.10", "12.10.2026", "12/10" (by [SlashDateOrder]), "2026-10-12",
 * "18:00", "18.00" (a time only when it is not a valid date, rule 6), "18".
 */
internal object NumericFormats {
    private val YEARS = 1900..2199

    fun date(text: String, slashOrder: SlashDateOrder): DateParts? {
        val groups = text.split('.', '/', '-', ':', ',')
        val separator = text.firstOrNull { it !in '0'..'9' } ?: return null
        if (text.any { it !in '0'..'9' && it != separator }) return null
        if (groups.any { it.isEmpty() }) return null
        val parts = when (groups.size) {
            2 -> twoGroups(groups[0], groups[1], separator, slashOrder)
            3 -> threeGroups(groups, separator, slashOrder)
            else -> null
        } ?: return null
        val maxDay = if (parts.year != null) {
            YearMonth.of(parts.year, parts.month).lengthOfMonth()
        } else {
            Month.of(parts.month).maxLength()
        }
        return if (parts.day <= maxDay) parts else null
    }

    private fun twoGroups(a: String, b: String, separator: Char, slashOrder: SlashDateOrder): DateParts? = when {
        // "12.10" is always day.month; a single-digit month ("1.5") is a decimal number, not a date.
        separator == '.' && a.length <= 2 && b.length == 2 -> dayMonth(a.toInt(), b.toInt(), null)
        // "1/2" is a fraction; at least one side of a slash date has two digits.
        separator == '/' && a.length <= 2 && b.length <= 2 && (a.length == 2 || b.length == 2) -> slash(a, b, null, slashOrder)
        else -> null
    }

    private fun threeGroups(groups: List<String>, separator: Char, slashOrder: SlashDateOrder): DateParts? {
        val (a, b, c) = groups
        val yearFirst = a.length == 4 && b.length <= 2 && c.length <= 2
        return when {
            yearFirst && separator in ".-/" -> year(a)?.let { dayMonth(c.toInt(), b.toInt(), it) }
            a.length > 2 || b.length > 2 -> null
            separator == '.' -> year(c)?.let { dayMonth(a.toInt(), b.toInt(), it) }
            separator == '/' -> year(c)?.let { slash(a, b, it, slashOrder) }
            else -> null
        }
    }

    private fun slash(a: String, b: String, year: Int?, order: SlashDateOrder): DateParts? = when (order) {
        SlashDateOrder.DAY_MONTH -> dayMonth(a.toInt(), b.toInt(), year)
        SlashDateOrder.MONTH_DAY -> dayMonth(b.toInt(), a.toInt(), year)
    }

    private fun dayMonth(day: Int, month: Int, year: Int?): DateParts? =
        if (day in 1..31 && month in 1..12) DateParts(day, month, year) else null

    private fun year(text: String): Int? = when (text.length) {
        2 -> 2000 + text.toInt()
        4 -> text.toInt().takeIf { it in YEARS }
        else -> null
    }

    /** "18", "18:00", "9:30", "24:00" (end of day, read as 23:59), or "18.00" when it is not a date. */
    fun clock(text: String): ClockParts? {
        if (text.all { it in '0'..'9' }) {
            return if (text.length <= 2) ClockParts(text.toInt(), 0, hasMinutes = false) else null
        }
        val separator = if (':' in text) ':' else '.'
        val hour = text.substringBefore(separator)
        val minute = text.substringAfter(separator)
        val wellFormed = hour.length in 1..2 && minute.length == 2 && (hour + minute).all { it in '0'..'9' }
        if (!wellFormed) return null
        if (separator == '.' && date(text, SlashDateOrder.DAY_MONTH) != null) return null
        val h = hour.toInt()
        val m = minute.toInt()
        return when {
            h == 24 && m == 0 -> ClockParts(23, 59, hasMinutes = true)
            h in 0..23 && m in 0..59 -> ClockParts(h, m, hasMinutes = true)
            else -> null
        }
    }
}
