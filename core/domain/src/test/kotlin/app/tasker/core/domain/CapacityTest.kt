package app.tasker.core.domain

import app.tasker.core.domain.capacity.CapacityCalculator
import app.tasker.core.domain.capacity.Intervals
import app.tasker.core.domain.capacity.TimeInterval
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AppSettings
import app.tasker.core.testing.TestTimeSource
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import org.junit.Test

class CapacityTest {
    private val source = TestTimeSource.at(LocalDateTime.of(2026, 10, 6, 8, 0))
    private val clock = DayClock(source)
    private val calculator = CapacityCalculator(clock)
    private val settings = AppSettings()
    private val today = date("2026-10-06")

    private fun interval(from: Int, to: Int, day: String = "2026-10-06") =
        TimeInterval(clock.at(date(day), from * 60), clock.at(date(day), to * 60))

    @Test
    fun `example from the plan - meetings and 30 percent buffer`() {
        val capacity = calculator.compute(today, settings, listOf(interval(10, 11), interval(14, 15)), calendarAvailable = true)
        assertThat(capacity.workingMin).isEqualTo(540)
        assertThat(capacity.busyMin).isEqualTo(120)
        assertThat(capacity.freeMin).isEqualTo(420)
        assertThat(capacity.capacityMin).isEqualTo(294)
    }

    @Test
    fun `only the remaining part of today counts`() {
        source.set(LocalDateTime.of(2026, 10, 6, 13, 0))
        val capacity = calculator.compute(today, settings, listOf(interval(10, 11), interval(14, 15)), calendarAvailable = true)
        assertThat(capacity.workingMin).isEqualTo(300)
        assertThat(capacity.busyMin).isEqualTo(60)
        assertThat(capacity.capacityMin).isEqualTo(168)
    }

    @Test
    fun `without calendar access busy time is zero`() {
        val capacity = calculator.compute(today, settings, listOf(interval(10, 11)), calendarAvailable = false)
        assertThat(capacity.capacityMin).isEqualTo(378)
        assertThat(capacity.calendarAvailable).isFalse()
    }

    @Test
    fun `non working day and evening have zero capacity`() {
        val saturday = calculator.compute(date("2026-10-10"), settings, emptyList(), calendarAvailable = true)
        assertThat(saturday.capacityMin).isEqualTo(0)
        assertThat(saturday.isWorkDay).isFalse()

        source.set(LocalDateTime.of(2026, 10, 6, 19, 0))
        assertThat(calculator.compute(today, settings, emptyList(), calendarAvailable = true).capacityMin).isEqualTo(0)
    }

    @Test
    fun `overlapping events are merged and clipped to the window`() {
        val busy = listOf(interval(8, 10), interval(9, 11), interval(10, 12), interval(17, 20))
        assertThat(Intervals.merge(busy)).hasSize(2)
        val capacity = calculator.compute(today, settings, busy, calendarAvailable = true)
        assertThat(capacity.busyMin).isEqualTo(4 * 60)
    }

    @Test
    fun `overload is planned minus capacity, never negative`() {
        val capacity = calculator.compute(today, settings, emptyList(), calendarAvailable = true)
        assertThat(capacity.overloadFor(capacity.capacityMin + 90)).isEqualTo(90)
        assertThat(capacity.overloadFor(10)).isEqualTo(0)
    }
}
