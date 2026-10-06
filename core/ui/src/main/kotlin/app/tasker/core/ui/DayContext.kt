package app.tasker.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.tasker.core.domain.time.DayClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay

/**
 * "Now" for derived UI state (overdue marks, relative dates). Provided by [ProvideDayContext] from [DayClock] and
 * refreshed every minute; components never read the system clock.
 */
@Immutable
data class DayContext(val today: LocalDate, val now: Instant, val zone: ZoneId)

/** Dynamic local: the minute tick recomposes only the composables that read it. */
val LocalDayContext = compositionLocalOf {
    DayContext(LocalDate.ofEpochDay(0), Instant.EPOCH, ZoneId.of("UTC"))
}

fun DayClock.dayContext(): DayContext = DayContext(today(), now(), zone())

/** Provides [LocalDayContext]: refreshed at every minute boundary and whenever the screen resumes (zone changes). */
@Composable
fun ProvideDayContext(clock: DayClock, content: @Composable () -> Unit) {
    var context by remember(clock) { mutableStateOf(clock.dayContext()) }
    LaunchedEffect(clock) {
        while (true) {
            val millis = clock.now().toEpochMilli()
            delay(MINUTE_MILLIS - millis % MINUTE_MILLIS)
            context = clock.dayContext()
        }
    }
    LifecycleResumeEffect(clock) {
        context = clock.dayContext()
        onPauseOrDispose { }
    }
    CompositionLocalProvider(LocalDayContext provides context, content = content)
}

private const val MINUTE_MILLIS = 60_000L
