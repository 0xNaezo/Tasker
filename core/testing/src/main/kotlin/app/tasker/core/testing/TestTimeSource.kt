package app.tasker.core.testing

import app.tasker.core.domain.time.DayClock
import app.tasker.core.domain.time.TimeSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Controllable clock for "after N days" scenarios (tech plan §4.2, §22). */
class TestTimeSource(
    private var current: Instant,
    private var zoneId: ZoneId = DEFAULT_ZONE,
) : TimeSource {
    override fun now(): Instant = current

    override fun zone(): ZoneId = zoneId

    fun set(instant: Instant) {
        current = instant
    }

    fun set(local: LocalDateTime) {
        current = local.atZone(zoneId).toInstant()
    }

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }

    fun advanceDays(days: Long) = advance(Duration.ofDays(days))

    fun advanceHours(hours: Long) = advance(Duration.ofHours(hours))

    fun setZone(zone: ZoneId) {
        zoneId = zone
    }

    companion object {
        val DEFAULT_ZONE: ZoneId = ZoneId.of("Europe/Kyiv")

        /** Tuesday 2026-10-06 10:00 — the reference "now" of the tech plan examples (§9.4). */
        val REFERENCE: LocalDateTime = LocalDateTime.of(2026, 10, 6, 10, 0)

        fun at(local: LocalDateTime = REFERENCE, zone: ZoneId = DEFAULT_ZONE) = TestTimeSource(local.atZone(zone).toInstant(), zone)
    }
}

/** A test time source and the day clock on top of it. */
class TestClock(local: LocalDateTime = TestTimeSource.REFERENCE, zone: ZoneId = TestTimeSource.DEFAULT_ZONE) {
    val source: TestTimeSource = TestTimeSource.at(local, zone)
    val clock: DayClock = DayClock(source)
}

fun date(text: String): LocalDate = LocalDate.parse(text)
