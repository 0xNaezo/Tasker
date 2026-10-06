package app.tasker.backend

import kotlin.math.roundToInt
import org.slf4j.LoggerFactory

/**
 * Alert lines for the PaaS log drain (tech plan §18.1, observability). Each starts with `ALERT <name>` so a
 * log-based alert rule can match it, and carries numbers only.
 */
object Alerts {
    const val LOGGER_NAME = "app.tasker.backend.alerts"
    private val log = LoggerFactory.getLogger(LOGGER_NAME)

    /** 80% of the daily budget is spent; logged once per UTC day across all instances. */
    fun budgetWarning(spentMicroUsd: Long, budgetMicroUsd: Long) =
        log.warn("ALERT ai_budget_warning spentMicroUsd={} budgetMicroUsd={}", spentMicroUsd, budgetMicroUsd)

    /** The daily budget is spent: `/v1/enrich` answers 503 until the next UTC day. Logged once per day. */
    fun budgetExhausted(spentMicroUsd: Long, budgetMicroUsd: Long) =
        log.error("ALERT ai_budget_exhausted spentMicroUsd={} budgetMicroUsd={}", spentMicroUsd, budgetMicroUsd)

    /** The share of failed provider calls crossed the threshold of [ErrorRateMonitor] on this instance. */
    fun errorRate(failedShare: Double, window: Int) =
        log.error("ALERT ai_error_rate failedPercent={} window={}", (failedShare * PERCENT).roundToInt(), window)

    private const val PERCENT = 100
}

/**
 * Share of failed provider calls over the last [window] calls of this instance. [record] reports the
 * share once when it reaches [threshold] (after at least [minCalls] calls) and re-arms when it falls below
 * half the threshold, so a long outage produces one alert line rather than one per request.
 */
class ErrorRateMonitor(
    val window: Int = DEFAULT_WINDOW,
    private val threshold: Double = DEFAULT_THRESHOLD,
    private val minCalls: Int = DEFAULT_MIN_CALLS,
) {
    private val outcomes = BooleanArray(window)
    private var next = 0
    private var count = 0
    private var failures = 0
    private var alerting = false

    init {
        require(window > 0 && minCalls in 1..window && threshold > 0.0 && threshold <= 1.0)
    }

    /** Adds one call; returns the failed share when an alert should be raised now, otherwise null. */
    @Synchronized
    fun record(failed: Boolean): Double? {
        if (count == window) {
            if (outcomes[next]) failures--
        } else {
            count++
        }
        outcomes[next] = failed
        if (failed) failures++
        next = (next + 1) % window
        val share = failures.toDouble() / count
        return when {
            !alerting && count >= minCalls && share >= threshold -> {
                alerting = true
                share
            }
            alerting && share < threshold / 2 -> {
                alerting = false
                null
            }
            else -> null
        }
    }

    companion object {
        const val DEFAULT_WINDOW = 50
        const val DEFAULT_THRESHOLD = 0.2
        const val DEFAULT_MIN_CALLS = 20
    }
}
