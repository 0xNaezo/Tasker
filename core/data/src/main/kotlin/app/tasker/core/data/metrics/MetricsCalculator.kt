package app.tasker.core.data.metrics

import app.tasker.core.data.mapper.toEpochDayLong
import app.tasker.core.data.mapper.toMillis
import app.tasker.core.database.TaskerDatabase
import app.tasker.core.database.dao.NameCount
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.ArchiveReason
import app.tasker.core.model.CaptureChannel
import app.tasker.core.model.PlanItemOutcome
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/**
 * One week of telemetry aggregates (tech plan §23): counts and numbers only, never texts or ids. Keys are short
 * machine names in the format `/v1/metrics` accepts; [values] holds the shares and the median, absent when undefined.
 */
data class WeeklyMetrics(val weekStart: LocalDate, val counters: Map<String, Long>, val values: Map<String, Double>)

/**
 * Computes the success metrics of the spec (ТЗ §11, tech plan §23) on the device. A week runs Monday to Sunday in
 * logical days, so work done after midnight counts towards the day the user lived.
 */
class MetricsCalculator @Inject constructor(
    private val db: TaskerDatabase,
    private val clock: DayClock,
) {
    /** The Monday of the last week that has ended. */
    fun lastFullWeek(): LocalDate = mondayOf(clock.today()).minusWeeks(1)

    /** The Monday of the week the app was first used, or null before the first start. */
    suspend fun firstWeek(): LocalDate? = db.metricsDao().firstActiveDay()?.let { mondayOf(LocalDate.ofEpochDay(it)) }

    suspend fun week(weekStart: LocalDate): WeeklyMetrics {
        require(weekStart.dayOfWeek == DayOfWeek.MONDAY) { "A week starts on Monday" }
        val dao = db.metricsDao()
        val weekEnd = weekStart.plusWeeks(1)
        val from = clock.dayStart(weekStart).toMillis()
        val to = clock.dayStart(weekEnd).toMillis()
        val fromDay = weekStart.toEpochDayLong()
        val toDay = weekEnd.toEpochDayLong()
        val counters = linkedMapOf(FORMAT_KEY to FORMAT_VERSION)
        val values = linkedMapOf<String, Double>()

        // Time spent on upkeep: review and Inbox triage sessions. A session left open on screen counts up to a cap.
        val sessions = dao.finishedSessions(fromDay, toDay)
        counters["maintenance.sessions"] = sessions.size.toLong()
        counters["maintenance.seconds"] = sessions.sumOf { (it.endedAt - it.startedAt).coerceIn(0, MAX_SESSION_MS) } / MS_PER_SECOND

        // Items of accepted plans by outcome; an item removed from the plan does not count as planned.
        val outcomes = dao.acceptedPlanOutcomes(fromDay, toDay).byName()
        PlanItemOutcome.entries.forEach { counters["plan.${it.key}"] = outcomes[it.name] ?: 0 }
        val planned = outcomes.filterKeys { it != PlanItemOutcome.REMOVED.name }.values.sum()
        val plannedDone = outcomes[PlanItemOutcome.DONE.name] ?: 0
        counters["plan.items"] = planned
        if (planned > 0) values["plan.done_share"] = plannedDone.toDouble() / planned

        // Postpones of the tasks finished (done or archived) during the week.
        val postpones = dao.postponeCountsOfFinished(from, to)
        counters["finished.tasks"] = postpones.size.toLong()
        repeat(POSTPONE_BINS) { bin -> counters["postpone.$bin"] = postpones.count { it == bin }.toLong() }
        counters["postpone.${POSTPONE_BINS}_plus"] = postpones.count { it >= POSTPONE_BINS }.toLong()
        median(postpones)?.let { values["postpone.median"] = it }

        // Where new tasks come from: the cheaper the capture, the more goes through the widget, sharing and integrations.
        val created = dao.createdByChannel(from, to).byName()
        CaptureChannel.entries.forEach { counters["created.${it.key}"] = created[it.name] ?: 0 }
        val createdTotal = created.values.sum()
        counters["created.total"] = createdTotal

        val archivedByTtl = dao.archivedWithReason(ArchiveReason.TTL_SKIPS, from, to)
        counters["archived.ttl"] = archivedByTtl
        if (createdTotal > 0) values["archived.ttl_share"] = archivedByTtl.toDouble() / createdTotal

        // The activity signal for retention: days the app was opened.
        counters["active.days"] = dao.activeDays(fromDay, toDay)
        return WeeklyMetrics(weekStart, counters, values)
    }

    companion object {
        /** Version of the key set, so the backend can tell reports of different app versions apart. */
        const val FORMAT_KEY = "format"
        const val FORMAT_VERSION = 1L

        /** A session longer than this was left open; it counts as this long. */
        val MAX_SESSION: Duration = Duration.ofMinutes(30)
        private val MAX_SESSION_MS = MAX_SESSION.toMillis()
        private const val MS_PER_SECOND = 1000L

        /** Postpone counts 0, 1 and 2 are counted apart, the rest together. */
        private const val POSTPONE_BINS = 3

        fun mondayOf(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        internal fun median(numbers: List<Int>): Double? {
            if (numbers.isEmpty()) return null
            val sorted = numbers.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle].toDouble() else (sorted[middle - 1] + sorted[middle]) / 2.0
        }

        private fun List<NameCount>.byName(): Map<String, Long> = associate { it.name to it.count }

        private val Enum<*>.key: String get() = name.lowercase()
    }
}
