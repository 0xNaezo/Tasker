package app.tasker.core.ai.contract

import kotlinx.serialization.Serializable

/**
 * Input of the `/v1/enrich` route (AI-1, CAP-2, CAP-7; tech plan §17.3).
 *
 * Privacy: only the task title travels, never notes, context snapshots or history. Similar completed
 * tasks are picked on the device and limited to [EnrichRoute.MAX_SIMILAR_TASKS].
 */
@Serializable
data class EnrichRequest(
    /** Task title after rule-based parsing (fragments the rules recognised are already removed). */
    val text: String,
    /** Language hint: "ru", "uk", "en" or null when unknown. */
    val language: String?,
    /** Current local date-time, ISO-8601 without offset, e.g. "2026-10-06T10:00". */
    val now: String,
    /** Logical day (04:00 boundary, tech plan §3 item 9), ISO date, e.g. "2026-10-06". */
    val today: String,
    /** IANA time zone of the device, e.g. "Europe/Kyiv". */
    val timeZone: String,
    /** The user's own durations of S/M/L (PLN-1, SET-1). */
    val estimateScale: EstimateScale,
    /** Fields the rule-based parser already extracted; AI never overrides them (AI-4, tech plan §8.3). */
    val extracted: ExtractedFields,
    /** Up to five similar completed tasks chosen on the device (AI-1). */
    val similarDone: List<SimilarTask> = emptyList(),
)

/** Minutes for S, M and L as configured by the user. */
@Serializable
data class EstimateScale(val s: Int, val m: Int, val l: Int)

/** Fields found by the rule-based parser. Dates are ISO dates, time is "HH:mm". */
@Serializable
data class ExtractedFields(
    val estimate: String? = null,
    val deadlineDate: String? = null,
    val deadlineTime: String? = null,
    val planDate: String? = null,
)

/** A completed task similar to the new one: its title, size and the minutes actually spent (if tracked). */
@Serializable
data class SimilarTask(val text: String, val estimate: String?, val actualMinutes: Int?)

/**
 * Output of `/v1/enrich`, already checked by [EnrichRoute.validate]. Every field is optional: a null
 * field simply stays empty in the app (tech plan §17.1).
 */
@Serializable
data class EnrichResponse(
    /** "S", "M" or "L". */
    val estimate: String? = null,
    /** Model's confidence in [estimate], 0..1. */
    val estimateConfidence: Double? = null,
    /** ISO date of a hard deadline found in the text. */
    val deadlineDate: String? = null,
    /** "HH:mm" local time of the deadline, only together with [deadlineDate]. */
    val deadlineTime: String? = null,
    /** The deadline expression exactly as it occurs in the task text. */
    val deadlineFragment: String? = null,
    /** ISO date the user plans to do the task. */
    val planDate: String? = null,
    /** The plan-date expression exactly as it occurs in the task text. */
    val planDateFragment: String? = null,
) {
    /** True when the response fills nothing. */
    val isEmpty: Boolean
        get() = estimate == null && deadlineDate == null && planDate == null
}
