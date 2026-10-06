package app.tasker.backend

import java.time.Clock

/**
 * The only place that reads the system clock (detekt rule ForbiddenClockCall). Everything else receives
 * a [Clock], so tests run with fixed or stepped time.
 */
object SystemTimeSource {
    val clock: Clock = Clock.systemUTC()
}
