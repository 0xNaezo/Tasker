package app.tasker.core.domain.port

import app.tasker.core.domain.capacity.TimeInterval
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Busy intervals of the device calendar inside a window (CAL-1, tech plan §10.2). */
data class BusyTime(
    val intervals: List<TimeInterval>,
    val calendarAvailable: Boolean,
) {
    companion object {
        /** No calendar access: busy time is zero and the header says "without calendar" (PLN-3). */
        val NONE = BusyTime(emptyList(), calendarAvailable = false)
    }
}

/**
 * Source of busy time for capacity. The calendar module implements it; without it (or without the permission)
 * capacity is working hours minus buffer.
 */
interface BusyTimeSource {
    /** Busy intervals overlapping [from, to). Never throws: on any error returns [BusyTime.NONE]. */
    suspend fun busy(from: Instant, to: Instant): BusyTime

    /** Emits when calendar data, the permission or the calendar selection change; consumers re-query [busy]. */
    fun changes(): Flow<Unit>
}

object NoCalendarBusyTimeSource : BusyTimeSource {
    override suspend fun busy(from: Instant, to: Instant): BusyTime = BusyTime.NONE

    override fun changes(): Flow<Unit> = emptyFlow()
}
