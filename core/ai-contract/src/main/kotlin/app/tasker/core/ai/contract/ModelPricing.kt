package app.tasker.core.ai.contract

/**
 * Prices in US cents per million tokens, so that all arithmetic stays in integers.
 * Cache writes are 5-minute writes (1.25x input), which is what the routes use.
 */
data class ModelPrice(
    val inputCentsPerMTok: Long,
    val outputCentsPerMTok: Long,
    val cacheReadCentsPerMTok: Long,
    val cacheWriteCentsPerMTok: Long,
)

/**
 * Cost accounting for the proxy budget and the eval (tech plan §17.5). Prices as of the plan date; re-check
 * before launch. Usage is converted to micro-USD: tokens x (USD per MTok) is exactly micro-USD.
 */
object ModelPricing {
    /** Model id (or alias) prefix -> price. Longest matching prefix wins, so dated ids resolve too. */
    val prices: Map<String, ModelPrice> = mapOf(
        // $4 / $20 per MTok; cache reads $0.20 (0.05x), cache writes $5.
        "claude-opus-5-5" to ModelPrice(400, 2_000, 20, 500),
        // $2 / $10; cache reads $0.20, cache writes $2.50.
        "claude-sonnet-5-5" to ModelPrice(200, 1_000, 20, 250),
        // $1 / $5; cache reads $0.10 (0.1x), cache writes $1.25.
        "claude-haiku-4-5" to ModelPrice(100, 500, 10, 125),
        // Server-side fallback targets for Claude Opus 5.5 refusals; billed at their own rates ($5 / $25).
        "claude-opus-5" to ModelPrice(500, 2_500, 50, 625),
        "claude-opus-4-8" to ModelPrice(500, 2_500, 50, 625),
    )

    /** Unknown models are priced at the most expensive known rates so the budget is never underestimated. */
    val unknownModelPrice: ModelPrice = ModelPrice(
        inputCentsPerMTok = prices.values.maxOf { it.inputCentsPerMTok },
        outputCentsPerMTok = prices.values.maxOf { it.outputCentsPerMTok },
        cacheReadCentsPerMTok = prices.values.maxOf { it.cacheReadCentsPerMTok },
        cacheWriteCentsPerMTok = prices.values.maxOf { it.cacheWriteCentsPerMTok },
    )

    fun priceFor(model: String): ModelPrice = prices.entries
        .filter { (id, _) -> model == id || model.startsWith("$id-") }
        .maxByOrNull { (id, _) -> id.length }
        ?.value
        ?: unknownModelPrice

    /** Cost of [usage] in micro-USD, rounded up; includes attempts declined before a fallback. */
    fun costMicroUsd(usage: RouteUsage): Long {
        val ownCentsTimesTokens = with(priceFor(usage.model)) {
            usage.inputTokens * inputCentsPerMTok +
                usage.outputTokens * outputCentsPerMTok +
                usage.cacheReadTokens * cacheReadCentsPerMTok +
                usage.cacheCreationTokens * cacheWriteCentsPerMTok
        }
        val own = ceilDiv(ownCentsTimesTokens, CENTS_PER_USD)
        return own + usage.declinedAttempts.sumOf(::costMicroUsd)
    }

    fun costUsd(usage: RouteUsage): Double = costMicroUsd(usage) / MICROS_PER_USD

    private fun ceilDiv(value: Long, divisor: Long): Long = (value + divisor - 1) / divisor

    private const val CENTS_PER_USD = 100L
    private const val MICROS_PER_USD = 1_000_000.0
}
