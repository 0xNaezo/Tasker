package app.tasker.core.domain.time

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Moves the app's clock by an offset that may change at any time. */
fun interface ClockShift {
    fun offset(): Duration
}

/**
 * Time of a debug build with a managed clock (tech plan §22.1, §24.1): "N days later" scenarios run against the real
 * app by shifting its clock instead of the device's. Release builds have no shift.
 */
class ShiftedTimeSource(private val base: TimeSource, private val shift: ClockShift) : TimeSource {
    override fun now(): Instant = base.now().plus(shift.offset())

    override fun zone(): ZoneId = base.zone()
}
