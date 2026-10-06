package app.tasker.core.backup

import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.IsoFields

/**
 * Which daily backups to keep (tech plan §16, NFR "Сохранность"): the [DAILY] newest copies, and among the older ones the
 * newest copy of each ISO week for the [WEEKLY] newest weeks. A pure function of dates, so the policy is tested without
 * files and applies the same way to the private folder and to the user-chosen one.
 */
object BackupRotation {
    const val DAILY = 7
    const val WEEKLY = 4

    /**
     * The dates out of [dates] whose copies are kept on [today].
     *
     * Copies dated after [today] were made while the device clock was wrong. They are kept and take no slot: deleting
     * them on a guess could lose the newest data, and they age into the normal policy once their date passes.
     */
    fun keep(
        dates: Collection<LocalDate>,
        today: LocalDate,
        daily: Int = DAILY,
        weekly: Int = WEEKLY,
    ): Set<LocalDate> {
        require(daily >= 0 && weekly >= 0) { "Counts must not be negative" }
        val (future, past) = dates.toSortedSet(Comparator.reverseOrder()).partition { it.isAfter(today) }
        val dailyCopies = past.take(daily)
        val weeklyCopies = past.drop(daily)
            .groupBy { isoWeek(it) } // newest first, so each group starts with the newest copy of its week
            .values
            .map { it.first() }
            .take(weekly)
        return LinkedHashSet<LocalDate>().apply {
            addAll(future)
            addAll(dailyCopies)
            addAll(weeklyCopies)
        }
    }

    /** The dates out of [dates] whose copies are deleted on [today]: everything [keep] does not keep. */
    fun expired(
        dates: Collection<LocalDate>,
        today: LocalDate,
        daily: Int = DAILY,
        weekly: Int = WEEKLY,
    ): Set<LocalDate> = dates.toSet() - keep(dates, today, daily, weekly)

    private fun isoWeek(date: LocalDate): Long =
        date.get(IsoFields.WEEK_BASED_YEAR) * WEEKS_PER_YEAR_KEY + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    /** Key multiplier: ISO years have at most 53 weeks. */
    private const val WEEKS_PER_YEAR_KEY = 100L
}

/** The date of a backup file name that matches [pattern] as a whole, its first group being `YYYY-MM-DD`; else null. */
internal fun dateInName(pattern: Regex, name: String): LocalDate? {
    val match = pattern.matchEntire(name) ?: return null
    return try {
        LocalDate.parse(match.groupValues[1])
    } catch (e: DateTimeException) {
        null
    }
}
