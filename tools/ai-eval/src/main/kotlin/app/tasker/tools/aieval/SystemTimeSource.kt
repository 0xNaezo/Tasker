package app.tasker.tools.aieval

import java.time.Clock

/** The only place of the eval that reads the system clock (detekt rule ForbiddenClockCall): the report date. */
object SystemTimeSource {
    val clock: Clock = Clock.systemUTC()
}
