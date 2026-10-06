package app.tasker.core.ai.contract

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelPricingTest {

    @Test
    fun `opus 5_5 cost uses discounted cache reads and 1_25x cache writes`() {
        val usage = RouteUsage(
            "anthropic/claude-opus-5.5",
            inputTokens = 1_000,
            outputTokens = 500,
            cacheReadTokens = 2_000,
            cacheCreationTokens = 1_000,
        )
        // 1000 x $4 + 500 x $20 + 2000 x $0.20 + 1000 x $5, per MTok = $0.0194
        assertThat(ModelPricing.costMicroUsd(usage)).isEqualTo(19_400)
        assertThat(ModelPricing.costUsd(usage)).isWithin(1e-9).of(0.0194)
    }

    @Test
    fun `cheaper models are priced from the table`() {
        val sonnet = RouteUsage("anthropic/claude-sonnet-5.5", 1_000_000, 0)
        val haiku = RouteUsage("anthropic/claude-haiku-4.5", 0, 1_000_000, cacheReadTokens = 1_000_000)
        assertThat(ModelPricing.costMicroUsd(sonnet)).isEqualTo(2_000_000)
        assertThat(ModelPricing.costMicroUsd(haiku)).isEqualTo(5_100_000)
    }

    @Test
    fun `dated and variant model ids resolve to the longest matching prefix`() {
        val haiku = ModelPricing.prices.getValue("anthropic/claude-haiku-4.5")
        assertThat(ModelPricing.priceFor("anthropic/claude-haiku-4.5-20251001")).isEqualTo(haiku)
        assertThat(ModelPricing.priceFor("anthropic/claude-haiku-4.5:nitro")).isEqualTo(haiku)
        assertThat(ModelPricing.priceFor("anthropic/claude-opus-5.5"))
            .isEqualTo(ModelPricing.prices.getValue("anthropic/claude-opus-5.5"))
        assertThat(ModelPricing.priceFor("anthropic/claude-opus-5.50")).isEqualTo(ModelPricing.unknownModelPrice)
    }

    @Test
    fun `unknown models are priced conservatively`() {
        val price = ModelPricing.priceFor("some/future-model")
        assertThat(price).isEqualTo(ModelPricing.unknownModelPrice)
        assertThat(price.outputCentsPerMTok).isEqualTo(2_000)
    }

    @Test
    fun `the cost the provider reported wins over the estimate`() {
        val reported = RouteUsage("anthropic/claude-opus-5.5", inputTokens = 1_000, outputTokens = 100, costMicroUsd = 7_123)
        assertThat(ModelPricing.costMicroUsd(reported)).isEqualTo(7_123)
        assertThat(ModelPricing.estimateMicroUsd(reported)).isEqualTo(4_000 + 2_000)
        assertThat(ModelPricing.costMicroUsd(reported.copy(costMicroUsd = 0))).isEqualTo(0)
    }

    @Test
    fun `estimates round up`() {
        assertThat(ModelPricing.costMicroUsd(RouteUsage("anthropic/claude-opus-5.5", 0, 0, cacheReadTokens = 1))).isEqualTo(1)
        assertThat(ModelPricing.costMicroUsd(RouteUsage("anthropic/claude-opus-5.5", 0, 0))).isEqualTo(0)
    }
}
