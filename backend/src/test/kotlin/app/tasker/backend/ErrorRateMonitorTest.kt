package app.tasker.backend

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ErrorRateMonitorTest {

    @Test
    fun `alerts once when the failed share crosses the threshold and re-arms after recovery`() {
        val monitor = ErrorRateMonitor(window = 10, threshold = 0.5, minCalls = 4)

        assertThat(monitor.record(failed = true)).isNull()
        assertThat(monitor.record(failed = true)).isNull()
        assertThat(monitor.record(failed = false)).isNull()
        assertThat(monitor.record(failed = false)).isEqualTo(0.5)
        assertThat(monitor.record(failed = true)).isNull()

        // Recovery: ten successes push the share below half the threshold.
        repeat(10) { assertThat(monitor.record(failed = false)).isNull() }
        repeat(4) { monitor.record(failed = true) }
        assertThat(monitor.record(failed = true)).isEqualTo(0.5)
    }

    @Test
    fun `old calls leave the window`() {
        val monitor = ErrorRateMonitor(window = 4, threshold = 0.75, minCalls = 4)
        repeat(4) { monitor.record(failed = false) }
        assertThat(monitor.record(failed = true)).isNull()
        assertThat(monitor.record(failed = true)).isNull()
        assertThat(monitor.record(failed = true)).isEqualTo(0.75)
    }
}
