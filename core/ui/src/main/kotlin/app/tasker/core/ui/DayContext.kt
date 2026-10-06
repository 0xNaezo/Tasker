package app.tasker.core.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Now" for derived UI state (overdue marks, relative dates). The app shell provides it from DayClock and refreshes it
 * every minute; components never read the system clock.
 */
@Immutable
data class DayContext(val today: LocalDate, val now: Instant, val zone: ZoneId)

val LocalDayContext = staticCompositionLocalOf {
    DayContext(LocalDate.ofEpochDay(0), Instant.EPOCH, ZoneId.of("UTC"))
}
