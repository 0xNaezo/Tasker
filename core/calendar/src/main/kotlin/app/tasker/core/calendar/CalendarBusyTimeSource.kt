package app.tasker.core.calendar

import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.domain.capacity.TimeInterval
import app.tasker.core.domain.port.BusyTime
import app.tasker.core.domain.port.BusyTimeSource
import app.tasker.core.domain.time.DayClock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Busy time from the device calendars (CAL-1, PLN-3, tech plan §10.2). Bound as the [BusyTimeSource] of the data
 * layer, which treats it as optional: without this module, the permission or on any error capacity is working hours
 * minus the buffer ([BusyTime.NONE]).
 */
@Singleton
class CalendarBusyTimeSource @Inject constructor(
    private val calendars: CalendarRepository,
    private val settings: SettingsRepository,
    private val clock: DayClock,
) : BusyTimeSource {
    override suspend fun busy(from: Instant, to: Instant): BusyTime {
        if (!calendars.hasPermission()) return BusyTime.NONE
        if (!to.isAfter(from)) return BusyTime(emptyList(), calendarAvailable = true)
        val current = settings.current()
        val devices = calendars.calendarsOrNull() ?: return BusyTime.NONE
        val selected = BusyTimeRules.selectedCalendarIds(devices, current.selectedCalendars)
        if (selected.isEmpty()) return BusyTime(emptyList(), calendarAvailable = true)
        // All-day events are stored in UTC, so a local day can reach into the neighbouring UTC days.
        val events = calendars.instances(from.minus(ALL_DAY_MARGIN), to.plus(ALL_DAY_MARGIN)) ?: return BusyTime.NONE
        val intervals = BusyTimeRules.busyIntervals(events, selected, current.tentativeIsBusy, clock.zone(), TimeInterval(from, to))
        return BusyTime(intervals, calendarAvailable = true)
    }

    /**
     * Emits after calendar data changes (a content observer while the permission is granted), when the permission
     * changes and when the calendar selection or the "tentative is busy" setting changes. Sync adapters write in
     * bursts, so emissions are debounced.
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    override fun changes(): Flow<Unit> = merge(
        calendars.permission.flatMapLatest { granted -> if (granted) calendars.providerChanges() else emptyFlow() },
        calendars.permission.drop(1).map { },
        settings.settings.map { it.selectedCalendars to it.tentativeIsBusy }.distinctUntilChanged().drop(1).map { },
    ).debounce(DEBOUNCE)

    private companion object {
        val ALL_DAY_MARGIN: Duration = Duration.ofDays(1)
        val DEBOUNCE = 500.milliseconds
    }
}
