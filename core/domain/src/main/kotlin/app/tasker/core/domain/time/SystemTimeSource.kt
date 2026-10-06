package app.tasker.core.domain.time

import java.time.Instant
import java.time.ZoneId

/** The only place that reads the system clock (enforced by the ForbiddenClockCall detekt rule). */
class SystemTimeSource : TimeSource {
    override fun now(): Instant = Instant.now()

    override fun zone(): ZoneId = ZoneId.systemDefault()
}
