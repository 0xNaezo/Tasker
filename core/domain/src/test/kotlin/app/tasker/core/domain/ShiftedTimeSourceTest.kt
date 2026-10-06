package app.tasker.core.domain

import app.tasker.core.domain.time.DayClock
import app.tasker.core.domain.time.ShiftedTimeSource
import app.tasker.core.testing.TestTimeSource
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.LocalDateTime
import org.junit.Test

class ShiftedTimeSourceTest {
    private val base = TestTimeSource.at()
    private var offset: Duration = Duration.ZERO
    private val clock = DayClock(ShiftedTimeSource(base) { offset })

    @Test
    fun `the shift moves the logical day and follows changes at once`() {
        base.set(LocalDateTime.of(2026, 10, 6, 12, 0))
        assertThat(clock.today()).isEqualTo(date("2026-10-06"))

        offset = Duration.ofDays(14)
        assertThat(clock.today()).isEqualTo(date("2026-10-20"))
        assertThat(clock.zone()).isEqualTo(base.zone())

        offset = Duration.ZERO
        assertThat(clock.today()).isEqualTo(date("2026-10-06"))
    }
}
