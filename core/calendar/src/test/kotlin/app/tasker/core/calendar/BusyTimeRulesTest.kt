package app.tasker.core.calendar

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import app.tasker.core.domain.capacity.TimeInterval
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

class BusyTimeRulesTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val losAngeles = ZoneId.of("America/Los_Angeles")

    private fun local(text: String, zone: ZoneId = kyiv): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun utc(text: String): Long = Instant.parse(text).toEpochMilli()

    private fun window(from: String, to: String, zone: ZoneId = kyiv) = TimeInterval(local(from, zone), local(to, zone))

    private fun timed(
        from: String,
        to: String,
        calendarId: Long = 1,
        availability: Int? = Events.AVAILABILITY_BUSY,
        status: Int? = Events.STATUS_CONFIRMED,
        self: Int? = Attendees.ATTENDEE_STATUS_ACCEPTED,
    ) = EventInstance(calendarId, local(from).toEpochMilli(), local(to).toEpochMilli(), false, availability, status, self)

    private fun allDay(firstDay: String, endDayExclusive: String, availability: Int? = Events.AVAILABILITY_BUSY) =
        EventInstance(1, utc("${firstDay}T00:00:00Z"), utc("${endDayExclusive}T00:00:00Z"), true, availability, null, null)

    private fun busy(
        vararg events: EventInstance,
        window: TimeInterval = window("2026-10-07T09:00", "2026-10-07T18:00"),
        calendars: Set<Long> = setOf(1L),
        tentativeIsBusy: Boolean = true,
        zone: ZoneId = kyiv,
    ) = BusyTimeRules.busyIntervals(events.toList(), calendars, tentativeIsBusy, zone, window)

    @Test
    fun `a confirmed busy meeting takes its time`() {
        assertThat(busy(timed("2026-10-07T10:00", "2026-10-07T11:00")))
            .containsExactly(TimeInterval(local("2026-10-07T10:00"), local("2026-10-07T11:00")))
    }

    @Test
    fun `free, cancelled and declined events take no time`() {
        assertThat(busy(timed("2026-10-07T10:00", "2026-10-07T11:00", availability = Events.AVAILABILITY_FREE))).isEmpty()
        assertThat(busy(timed("2026-10-07T10:00", "2026-10-07T11:00", status = Events.STATUS_CANCELED))).isEmpty()
        assertThat(busy(timed("2026-10-07T10:00", "2026-10-07T11:00", self = Attendees.ATTENDEE_STATUS_DECLINED))).isEmpty()
    }

    @Test
    fun `tentative events follow the setting`() {
        val tentative = timed("2026-10-07T10:00", "2026-10-07T11:00", availability = Events.AVAILABILITY_TENTATIVE)
        val maybe = timed("2026-10-07T12:00", "2026-10-07T13:00", self = Attendees.ATTENDEE_STATUS_TENTATIVE)
        assertThat(busy(tentative, maybe, tentativeIsBusy = true)).hasSize(2)
        assertThat(busy(tentative, maybe, tentativeIsBusy = false)).isEmpty()
    }

    @Test
    fun `missing availability and status mean busy, the provider defaults`() {
        val unknown = timed("2026-10-07T10:00", "2026-10-07T11:00", availability = null, status = null, self = null)
        assertThat(busy(unknown)).hasSize(1)
        // Status TENTATIVE (0) is a common default of sync adapters and does not make an event tentative.
        assertThat(busy(timed("2026-10-07T10:00", "2026-10-07T11:00", status = Events.STATUS_TENTATIVE), tentativeIsBusy = false))
            .hasSize(1)
    }

    @Test
    fun `events of unselected calendars take no time`() {
        assertThat(busy(timed("2026-10-07T10:00", "2026-10-07T11:00", calendarId = 2))).isEmpty()
    }

    @Test
    fun `overlapping and touching meetings merge and are clipped to the window`() {
        val result = busy(
            timed("2026-10-07T08:00", "2026-10-07T09:30"),
            timed("2026-10-07T10:00", "2026-10-07T11:00"),
            timed("2026-10-07T10:30", "2026-10-07T12:00"),
            timed("2026-10-07T12:00", "2026-10-07T12:30"),
            timed("2026-10-07T17:30", "2026-10-07T19:00"),
        )
        assertThat(result).containsExactly(
            TimeInterval(local("2026-10-07T09:00"), local("2026-10-07T09:30")),
            TimeInterval(local("2026-10-07T10:00"), local("2026-10-07T12:30")),
            TimeInterval(local("2026-10-07T17:30"), local("2026-10-07T18:00")),
        ).inOrder()
    }

    @Test
    fun `broken timed events are ignored`() {
        assertThat(busy(timed("2026-10-07T11:00", "2026-10-07T10:00"))).isEmpty()
        assertThat(busy(timed("2026-10-07T11:00", "2026-10-07T11:00"))).isEmpty()
    }

    @Test
    fun `a busy all-day event covers the whole local day, converted from UTC`() {
        // In Los Angeles the naive reading of the UTC instants would cover only 09:00-17:00 of the window.
        val event = allDay("2026-10-07", "2026-10-08")
        val laWindow = window("2026-10-07T09:00", "2026-10-07T18:00", losAngeles)
        assertThat(busy(event, window = laWindow, zone = losAngeles)).containsExactly(laWindow)
        assertThat(BusyTimeRules.interval(event, kyiv))
            .isEqualTo(TimeInterval(local("2026-10-07T00:00"), local("2026-10-08T00:00")))
    }

    @Test
    fun `an all-day event of the previous day does not leak into the next local morning`() {
        // Naively 2026-10-06T00:00Z..2026-10-07T00:00Z would end at 03:00 Kyiv time on 7 October.
        val event = allDay("2026-10-06", "2026-10-07")
        assertThat(busy(event, window = window("2026-10-07T00:00", "2026-10-07T04:00"))).isEmpty()
    }

    @Test
    fun `free all-day events like birthdays take no time`() {
        assertThat(busy(allDay("2026-10-07", "2026-10-08", availability = Events.AVAILABILITY_FREE))).isEmpty()
    }

    @Test
    fun `multi-day and malformed all-day events`() {
        assertThat(BusyTimeRules.interval(allDay("2026-10-06", "2026-10-09"), kyiv))
            .isEqualTo(TimeInterval(local("2026-10-06T00:00"), local("2026-10-09T00:00")))
        val noEnd = EventInstance(1, utc("2026-10-07T00:00:00Z"), utc("2026-10-07T00:00:00Z"), true, null, null, null)
        assertThat(BusyTimeRules.interval(noEnd, kyiv))
            .isEqualTo(TimeInterval(local("2026-10-07T00:00"), local("2026-10-08T00:00")))
    }

    @Test
    fun `without an explicit choice all visible calendars count`() {
        val work = calendar(1, "com.google/me@example.com/me@example.com", visible = true)
        val hidden = calendar(2, "com.google/me@example.com/holidays", visible = false)
        val local = calendar(3, "LOCAL/Phone/Personal", visible = true)
        val all = listOf(work, hidden, local)
        assertThat(BusyTimeRules.selectedCalendarIds(all, null)).containsExactly(1L, 3L)
        assertThat(BusyTimeRules.selectedCalendarIds(all, setOf(hidden.key))).containsExactly(2L)
        assertThat(BusyTimeRules.selectedCalendarIds(all, setOf("3"))).containsExactly(3L)
        assertThat(BusyTimeRules.selectedCalendarIds(all, emptySet())).isEmpty()
    }

    @Test
    fun `selection key survives a re-sync and falls back for local calendars`() {
        assertThat(BusyTimeRules.selectionKey("com.google", "me@example.com", "team@group.calendar.google.com", "Team", 12))
            .isEqualTo("com.google/me@example.com/team@group.calendar.google.com")
        assertThat(BusyTimeRules.selectionKey("LOCAL", "Phone", null, "Personal", 3)).isEqualTo("LOCAL/Phone/Personal")
        assertThat(BusyTimeRules.selectionKey(null, null, null, null, 7)).isEqualTo("//#7")
    }

    private fun calendar(id: Long, key: String, visible: Boolean) =
        DeviceCalendar(id, key, "Calendar $id", "me@example.com", "com.google", 0, visible)
}
