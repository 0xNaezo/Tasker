package app.tasker.core.domain

import app.tasker.core.domain.time.DayClock
import app.tasker.core.testing.TestTimeSource
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

class DayClockTest {
    private val source = TestTimeSource.at()
    private val clock = DayClock(source)

    @Test
    fun `work after midnight belongs to the previous logical day`() {
        source.set(LocalDateTime.of(2026, 10, 7, 3, 59))
        assertThat(clock.today()).isEqualTo(date("2026-10-06"))
        source.set(LocalDateTime.of(2026, 10, 7, 4, 0))
        assertThat(clock.today()).isEqualTo(date("2026-10-07"))
    }

    @Test
    fun `boundary is configurable`() {
        clock.boundaryMinutes = 0
        source.set(LocalDateTime.of(2026, 10, 7, 0, 30))
        assertThat(clock.today()).isEqualTo(date("2026-10-07"))
    }

    @Test
    fun `day start and end follow the boundary across autumn DST change`() {
        // Europe/Kyiv leaves summer time on 2026-10-25 at 04:00 -> 03:00.
        val day = date("2026-10-25")
        val start = clock.dayStart(day)
        val end = clock.dayEnd(day)
        assertThat(clock.logicalDay(start)).isEqualTo(day)
        assertThat(clock.logicalDay(end.minusSeconds(1))).isEqualTo(day)
        assertThat(clock.logicalDay(end)).isEqualTo(day.plusDays(1))
    }

    @Test
    fun `wall clock in a spring DST gap moves forward`() {
        // 2027-03-28 03:00 -> 04:00 in Europe/Kyiv; 03:30 does not exist.
        val instant = clock.at(date("2027-03-28"), 3 * 60 + 30)
        assertThat(clock.local(instant).hour).isEqualTo(4)
    }

    @Test
    fun `changing the zone changes today but not stored instants`() {
        source.set(LocalDateTime.of(2026, 10, 6, 23, 30))
        val instant = source.now()
        assertThat(clock.today()).isEqualTo(date("2026-10-06"))
        source.setZone(ZoneId.of("Asia/Tokyo"))
        assertThat(source.now()).isEqualTo(instant)
        assertThat(clock.today()).isEqualTo(date("2026-10-07"))
    }

    @Test
    fun `days since counts logical days`() {
        val touched = source.now()
        source.advanceDays(7)
        assertThat(clock.daysSince(touched)).isEqualTo(7)
    }
}
