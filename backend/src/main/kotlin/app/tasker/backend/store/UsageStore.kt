package app.tasker.backend.store

import app.tasker.backend.db.Database
import app.tasker.backend.db.queryOne
import app.tasker.backend.db.update
import app.tasker.core.ai.contract.RouteUsage
import java.time.LocalDate

/** Result of reserving one AI request before calling the provider. */
enum class Reservation { GRANTED, UNKNOWN_INSTALL, BLOCKED, DAILY_LIMIT, BUDGET_EXHAUSTED }

enum class CallOutcome { SUCCEEDED, REFUSED, FAILED }

/** Budget thresholds crossed by the call just recorded; each fires once per day across all instances. */
data class BudgetAlerts(val reachedWarning: Boolean, val exhausted: Boolean, val spentMicroUsd: Long)

data class DailyUsage(
    val requests: Int,
    val succeeded: Int,
    val refused: Int,
    val failed: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheCreationTokens: Long,
    val costMicroUsd: Long,
    val latencyMsTotal: Long,
    val latencyMsMax: Long,
)

/**
 * Per-install daily limits and the global daily budget, persisted in the database so that they survive
 * restarts and work across instances (tech plan §18.1). Counters change with atomic conditional updates.
 *
 * The budget is checked before a call with the cost recorded so far; concurrent in-flight calls can
 * overshoot it by their own cost, which is bounded by `max_tokens`.
 */
class UsageStore(private val database: Database) {

    fun reserve(installId: String, day: LocalDate, dailyLimit: Int, budgetMicroUsd: Long): Reservation =
        database.transaction { connection ->
            when (InstallStore.state(connection, installId)) {
                null -> return@transaction Reservation.UNKNOWN_INSTALL
                InstallState.BLOCKED -> return@transaction Reservation.BLOCKED
                InstallState.ACTIVE -> Unit
            }
            val spent = connection.queryOne("SELECT cost_micro_usd FROM global_daily_cost WHERE cost_day = ?", day) { it.getLong(1) }
            if ((spent ?: 0L) >= budgetMicroUsd) return@transaction Reservation.BUDGET_EXHAUSTED

            connection.update("INSERT INTO daily_usage (install_id, usage_day) VALUES (?, ?) ON CONFLICT DO NOTHING", installId, day)
            val counted = connection.update(
                "UPDATE daily_usage SET requests = requests + 1 WHERE install_id = ? AND usage_day = ? AND requests < ?",
                installId,
                day,
                dailyLimit,
            )
            if (counted == 0) return@transaction Reservation.DAILY_LIMIT

            connection.update("INSERT INTO global_daily_cost (cost_day) VALUES (?) ON CONFLICT DO NOTHING", day)
            connection.update("UPDATE global_daily_cost SET requests = requests + 1 WHERE cost_day = ?", day)
            Reservation.GRANTED
        }

    /** Adds the outcome, tokens, cost and latency of a reserved call to the install's day and to the global day. */
    fun record(
        installId: String,
        day: LocalDate,
        outcome: CallOutcome,
        usage: RouteUsage?,
        costMicroUsd: Long,
        latencyMs: Long,
        budgetMicroUsd: Long,
    ): BudgetAlerts = database.transaction { connection ->
        connection.update(
            """
            UPDATE daily_usage SET
                succeeded = succeeded + ?, refused = refused + ?, failed = failed + ?,
                input_tokens = input_tokens + ?, output_tokens = output_tokens + ?,
                cache_read_tokens = cache_read_tokens + ?, cache_creation_tokens = cache_creation_tokens + ?,
                cost_micro_usd = cost_micro_usd + ?,
                latency_ms_total = latency_ms_total + ?, latency_ms_max = GREATEST(latency_ms_max, ?)
            WHERE install_id = ? AND usage_day = ?
            """.trimIndent(),
            if (outcome == CallOutcome.SUCCEEDED) 1 else 0,
            if (outcome == CallOutcome.REFUSED) 1 else 0,
            if (outcome == CallOutcome.FAILED) 1 else 0,
            usage?.inputTokens ?: 0L,
            usage?.outputTokens ?: 0L,
            usage?.cacheReadTokens ?: 0L,
            usage?.cacheCreationTokens ?: 0L,
            costMicroUsd,
            latencyMs,
            latencyMs,
            installId,
            day,
        )
        connection.update("UPDATE global_daily_cost SET cost_micro_usd = cost_micro_usd + ? WHERE cost_day = ?", costMicroUsd, day)
        val warning = connection.update(
            "UPDATE global_daily_cost SET warned_at_80 = TRUE WHERE cost_day = ? AND warned_at_80 = FALSE AND cost_micro_usd >= ?",
            day,
            budgetMicroUsd * WARNING_PERCENT / 100,
        ) > 0
        val exhausted = connection.update(
            "UPDATE global_daily_cost SET exhausted_alerted = TRUE " +
                "WHERE cost_day = ? AND exhausted_alerted = FALSE AND cost_micro_usd >= ?",
            day,
            budgetMicroUsd,
        ) > 0
        val spent = connection.queryOne("SELECT cost_micro_usd FROM global_daily_cost WHERE cost_day = ?", day) { it.getLong(1) }
        BudgetAlerts(reachedWarning = warning, exhausted = exhausted, spentMicroUsd = spent ?: 0L)
    }

    fun dailyUsage(installId: String, day: LocalDate): DailyUsage? = database.transaction { connection ->
        connection.queryOne(
            "SELECT requests, succeeded, refused, failed, input_tokens, output_tokens, cache_read_tokens, cache_creation_tokens, " +
                "cost_micro_usd, latency_ms_total, latency_ms_max FROM daily_usage WHERE install_id = ? AND usage_day = ?",
            installId,
            day,
        ) { row ->
            DailyUsage(
                requests = row.getInt(1),
                succeeded = row.getInt(2),
                refused = row.getInt(3),
                failed = row.getInt(4),
                inputTokens = row.getLong(5),
                outputTokens = row.getLong(6),
                cacheReadTokens = row.getLong(7),
                cacheCreationTokens = row.getLong(8),
                costMicroUsd = row.getLong(9),
                latencyMsTotal = row.getLong(10),
                latencyMsMax = row.getLong(11),
            )
        }
    }

    /** Total recorded cost of [day] in micro-USD (0 when nothing was recorded). */
    fun globalCost(day: LocalDate): Long = database.transaction { connection ->
        connection.queryOne("SELECT cost_micro_usd FROM global_daily_cost WHERE cost_day = ?", day) { it.getLong(1) } ?: 0L
    }

    private companion object {
        const val WARNING_PERCENT = 80
    }
}
