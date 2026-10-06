package app.tasker.core.calendar

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import app.tasker.core.domain.capacity.Intervals
import app.tasker.core.domain.capacity.TimeInterval
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** A calendar of the device, for the settings screen and the busy-time filter (PLN-3). */
data class DeviceCalendar(
    /** Row id in `CalendarContract.Calendars`; changes when the account is synced from scratch. */
    val id: Long,
    /**
     * Stable selection key built from the account and the calendar's sync id (tech plan §10.2: the choice survives a
     * re-sync of the account). Store this value in `AppSettings.selectedCalendars`.
     */
    val key: String,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    /** ARGB color of the calendar. */
    val color: Int,
    /** Shown in the system calendar app; with no explicit selection only visible calendars count. */
    val visible: Boolean,
)

/** One row of `CalendarContract.Instances` as plain data: recurring events are already expanded by the provider. */
data class EventInstance(
    val calendarId: Long,
    /** Epoch millis; all-day events start at UTC midnight of their first day. */
    val begin: Long,
    /** Epoch millis, exclusive; all-day events end at UTC midnight after their last day. */
    val end: Long,
    val allDay: Boolean,
    /** `Events.AVAILABILITY_*`; `null` means the provider default, busy. */
    val availability: Int?,
    /** `Events.STATUS_*`; only a cancelled event is ignored. */
    val status: Int?,
    /** `Attendees.ATTENDEE_STATUS_*` of the device owner. */
    val selfAttendeeStatus: Int?,
)

/**
 * Which calendar events take time (CAL-1, tech plan §10.2). Pure, so the rules are tested without a provider.
 *
 * An event is busy when its calendar is selected, it is not cancelled, the owner did not decline it and its
 * availability is "busy". A tentative event — availability TENTATIVE or the owner's answer "maybe" — is busy only
 * with the "tentative events are busy" setting; a free event never is. The event status TENTATIVE is not used for
 * this: its code is 0, which some providers write by default. All-day events follow the same availability rule, so
 * the usual free all-day events (birthdays, holidays) take no time.
 */
object BusyTimeRules {
    /**
     * Ids of the calendars that count. `null` [selection] means "all visible calendars" (the default, §7.7). An explicit
     * selection holds [DeviceCalendar.key] values; plain row ids are accepted too, so a selection saved by id still works.
     */
    fun selectedCalendarIds(calendars: Collection<DeviceCalendar>, selection: Set<String>?): Set<Long> {
        val selected = if (selection == null) {
            calendars.filter { it.visible }
        } else {
            calendars.filter { it.key in selection || it.id.toString() in selection }
        }
        return selected.mapTo(LinkedHashSet()) { it.id }
    }

    fun isBusy(event: EventInstance, tentativeIsBusy: Boolean): Boolean {
        if (event.status == Events.STATUS_CANCELED) return false
        if (event.selfAttendeeStatus == Attendees.ATTENDEE_STATUS_DECLINED) return false
        if (event.availability == Events.AVAILABILITY_FREE) return false
        val tentative = event.availability == Events.AVAILABILITY_TENTATIVE ||
            event.selfAttendeeStatus == Attendees.ATTENDEE_STATUS_TENTATIVE
        return !tentative || tentativeIsBusy
    }

    /**
     * Time an instance takes in [zone]. All-day events are stored as UTC dates and cover whole local days, so an
     * event on 7 October is busy from local midnight to local midnight in every time zone.
     */
    fun interval(event: EventInstance, zone: ZoneId): TimeInterval? {
        if (!event.allDay) {
            if (event.end <= event.begin) return null
            return TimeInterval(Instant.ofEpochMilli(event.begin), Instant.ofEpochMilli(event.end))
        }
        val first = utcDate(event.begin)
        val endExclusive = utcDate(event.end).takeIf { it.isAfter(first) } ?: first.plusDays(1)
        return TimeInterval(first.atStartOfDay(zone).toInstant(), endExclusive.atStartOfDay(zone).toInstant())
    }

    /** Merged busy intervals of the selected calendars inside [window]. */
    fun busyIntervals(
        events: Collection<EventInstance>,
        calendarIds: Set<Long>,
        tentativeIsBusy: Boolean,
        zone: ZoneId,
        window: TimeInterval,
    ): List<TimeInterval> {
        val busy = events
            .filter { it.calendarId in calendarIds && isBusy(it, tentativeIsBusy) }
            .mapNotNull { interval(it, zone) }
        return Intervals.clip(busy, window)
    }

    /**
     * Selection key of a calendar: account type, account name and the server id of the calendar, which survive a full
     * re-sync, unlike the row id. Local calendars without a sync id fall back to their name, then to the row id.
     */
    fun selectionKey(accountType: String?, accountName: String?, syncId: String?, name: String?, id: Long): String =
        listOf(accountType.orEmpty(), accountName.orEmpty(), syncId ?: name ?: "#$id").joinToString(KEY_SEPARATOR)

    private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate()

    private const val KEY_SEPARATOR = "/"
}
