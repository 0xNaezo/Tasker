package app.tasker.core.ai.contract

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProxyProtocolTest {

    @Test
    fun `integrity request hash is stable, url-safe and bound to the install id`() {
        val hash = IntegrityBinding.requestHash("0b9a5c1e-3f7d-4c8a-9e21-5d6f7a8b9c0d")
        assertThat(hash).hasLength(43)
        assertThat(hash).matches("[A-Za-z0-9_-]+")
        assertThat(IntegrityBinding.requestHash("0b9a5c1e-3f7d-4c8a-9e21-5d6f7a8b9c0d")).isEqualTo(hash)
        assertThat(IntegrityBinding.requestHash("0b9a5c1e-3f7d-4c8a-9e21-5d6f7a8b9c0e")).isNotEqualTo(hash)
    }

    @Test
    fun `install ids are short machine identifiers`() {
        assertThat(InstallIds.isValid("0b9a5c1e-3f7d-4c8a-9e21-5d6f7a8b9c0d")).isTrue()
        assertThat(InstallIds.isValid("short")).isFalse()
        assertThat(InstallIds.isValid("has spaces in it")).isFalse()
        assertThat(InstallIds.isValid("x".repeat(65))).isFalse()
    }

    @Test
    fun `metrics report accepts numeric aggregates for an ISO week`() {
        val report = MetricsReport(
            installId = "0b9a5c1e-3f7d-4c8a-9e21-5d6f7a8b9c0d",
            weekStart = "2026-10-05",
            counters = mapOf("tasks_created" to 12, "capture.widget" to 3),
            values = mapOf("plan_done_share" to 0.75),
        )
        assertThat(report.problems()).isEmpty()
    }

    @Test
    fun `metrics report rejects free text, bad numbers and non-Monday weeks`() {
        val base = MetricsReport("0b9a5c1e-3f7d-4c8a-9e21-5d6f7a8b9c0d", "2026-10-05")
        assertThat(base.copy(weekStart = "2026-10-06").problems()).containsExactly("weekStart")
        assertThat(base.copy(weekStart = "last week").problems()).containsExactly("weekStart")
        assertThat(base.copy(counters = mapOf("купить молоко" to 1)).problems()).containsExactly("counters")
        assertThat(base.copy(counters = mapOf("tasks" to -1)).problems()).containsExactly("counters")
        assertThat(base.copy(values = mapOf("share" to Double.NaN)).problems()).containsExactly("values")
        val tooMany = (0..MetricsReport.MAX_ENTRIES).associate { "k$it" to 1L }
        assertThat(base.copy(counters = tooMany).problems()).containsExactly("counters")
        assertThat(base.copy(installId = "?").problems()).containsExactly("installId")
    }

    @Test
    fun `failure kinds mark what is worth retrying`() {
        assertThat(FailureKind.entries.filter { it.retryable })
            .containsExactly(FailureKind.RATE_LIMIT, FailureKind.OVERLOADED, FailureKind.NETWORK)
    }
}
