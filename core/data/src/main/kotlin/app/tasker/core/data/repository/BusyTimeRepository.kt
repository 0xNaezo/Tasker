package app.tasker.core.data.repository

import app.tasker.core.domain.capacity.Capacity
import app.tasker.core.domain.capacity.CapacityCalculator
import app.tasker.core.domain.port.BusyTime
import app.tasker.core.domain.port.BusyTimeSource
import app.tasker.core.domain.port.NoCalendarBusyTimeSource
import app.tasker.core.model.AppSettings
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart

/** Busy time from the calendar module if it is installed, otherwise none (PLN-3). */
@Singleton
class BusyTimeRepository @Inject constructor(
    source: Optional<BusyTimeSource>,
    private val calculator: CapacityCalculator,
) {
    private val source: BusyTimeSource = source.orElse(NoCalendarBusyTimeSource)

    suspend fun busy(from: Instant, to: Instant): BusyTime = runCatching { source.busy(from, to) }.getOrDefault(BusyTime.NONE)

    /** Emits once at start and on every calendar change. */
    fun changes(): Flow<Unit> = source.changes().onStart { emit(Unit) }

    /** Busy time inside the working window of [day] (whole window, not only the remaining part). */
    suspend fun busyFor(day: LocalDate, settings: AppSettings): BusyTime {
        val window = calculator.workWindow(day, settings) ?: return BusyTime(emptyList(), calendarAvailable = isAvailable())
        return busy(window.start, window.end)
    }

    suspend fun capacity(day: LocalDate, settings: AppSettings, now: Instant): Capacity {
        val busy = busyFor(day, settings)
        return calculator.compute(day, settings, busy.intervals, busy.calendarAvailable, now)
    }

    private suspend fun isAvailable(): Boolean = busy(Instant.EPOCH, Instant.EPOCH).calendarAvailable
}
