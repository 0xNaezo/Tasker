package app.tasker.core.ai.contract

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelPricingTest {

    @Test
    fun `opus 5_5 cost uses discounted cache reads and 1_25x cache writes`() {
        val usage =
            RouteUsage("claude-opus-5-5", inputTokens = 1_000, outputTokens = 500, cacheReadTokens = 2_000, cacheCreationTokens = 1_000)
        // 1000 x $4 + 500 x $20 + 2000 x $0.20 + 1000 x $5, per MTok = $0.0194
        assertThat(ModelPricing.costMicroUsd(usage)).isEqualTo(19_400)
        assertThat(ModelPricing.costUsd(usage)).isWithin(1e-9).of(0.0194)
    }

    @Test
    fun `cheaper models are priced from the table`() {
        val sonnet = RouteUsage("claude-sonnet-5-5", 1_000_000, 0)
        val haiku = RouteUsage("claude-haiku-4-5", 0, 1_000_000, cacheReadTokens = 1_000_000)
        assertThat(ModelPricing.costMicroUsd(sonnet)).isEqualTo(2_000_000)
        assertThat(ModelPricing.costMicroUsd(haiku)).isEqualTo(5_100_000)
    }

    @Test
    fun `dated model ids resolve to the longest matching prefix`() {
        assertThat(ModelPricing.priceFor("claude-haiku-4-5-20251001")).isEqualTo(ModelPricing.prices.getValue("claude-haiku-4-5"))
        assertThat(ModelPricing.priceFor("claude-opus-5-5")).isEqualTo(ModelPricing.prices.getValue("claude-opus-5-5"))
        assertThat(ModelPricing.priceFor("claude-opus-5")).isEqualTo(ModelPricing.prices.getValue("claude-opus-5"))
        assertThat(ModelPricing.priceFor("claude-opus-5-50")).isEqualTo(ModelPricing.prices.getValue("claude-opus-5"))
    }

    @Test
    fun `unknown models are priced conservatively`() {
        val price = ModelPricing.priceFor("some-future-model")
        assertThat(price).isEqualTo(ModelPricing.unknownModelPrice)
        assertThat(price.outputCentsPerMTok).isEqualTo(2_500)
    }

    @Test
    fun `cost rounds up and includes attempts declined before a fallback`() {
        assertThat(ModelPricing.costMicroUsd(RouteUsage("claude-opus-5-5", 0, 0, cacheReadTokens = 1))).isEqualTo(1)
        val declined = RouteUsage("claude-opus-5-5", inputTokens = 1_000, outputTokens = 0)
        val served = RouteUsage("claude-opus-5", inputTokens = 1_000, outputTokens = 100, declinedAttempts = listOf(declined))
        assertThat(ModelPricing.costMicroUsd(served)).isEqualTo(4_000 + 5_000 + 2_500)
        assertThat(served.totalInputTokens).isEqualTo(2_000)
        assertThat(served.totalOutputTokens).isEqualTo(100)
    }
}
