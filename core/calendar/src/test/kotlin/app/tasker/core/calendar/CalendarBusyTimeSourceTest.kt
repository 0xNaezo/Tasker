package app.tasker.core.calendar

import android.Manifest
import android.app.Application
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import androidx.datastore.core.DataStore
import androidx.test.core.app.ApplicationProvider
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.domain.capacity.TimeInterval
import app.tasker.core.domain.port.BusyTime
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AppSettings
import app.tasker.core.testing.TestTimeSource
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private class SettingsStore(initial: AppSettings) : DataStore<AppSettings> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<AppSettings> = state

    override suspend fun updateData(transform: suspend (t: AppSettings) -> AppSettings): AppSettings =
        transform(state.value).also { state.value = it }
}

@RunWith(RobolectricTestRunner::class)
class CalendarBusyTimeSourceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val provider = Robolectric.buildContentProvider(FakeCalendarProvider::class.java).create(CalendarContract.AUTHORITY).get()
    private val time = TestTimeSource.at(LocalDateTime.of(2026, 10, 7, 8, 0))
    private val clock = DayClock(time)
    private val store = SettingsStore(AppSettings())

    private fun local(text: String): Instant = LocalDateTime.parse(text).atZone(clock.zone()).toInstant()

    private fun meeting(from: String, to: String, calendarId: Long = 1, availability: Int = Events.AVAILABILITY_BUSY) =
        EventInstance(
            calendarId,
            local(from).toEpochMilli(),
            local(to).toEpochMilli(),
            false,
            availability,
            Events.STATUS_CONFIRMED,
            Attendees.ATTENDEE_STATUS_ACCEPTED,
        )

    private fun source(scope: TestScope): Pair<CalendarRepository, CalendarBusyTimeSource> {
        val repository = CalendarRepository(app, StandardTestDispatcher(scope.testScheduler))
        return repository to CalendarBusyTimeSource(repository, SettingsRepository(store, clock), clock)
    }

    private fun grant() = shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)

    private val window get() = local("2026-10-07T09:00") to local("2026-10-07T18:00")

    @Test
    fun `without the permission there is no calendar and nothing is queried`() = runTest {
        provider.calendars = listOf(CalendarRow(1, "Work"))
        provider.instances = listOf(meeting("2026-10-07T10:00", "2026-10-07T11:00"))
        val (repository, source) = source(this)

        assertThat(source.busy(window.first, window.second)).isEqualTo(BusyTime.NONE)
        assertThat(repository.calendars()).isEmpty()
        assertThat(provider.instanceQueries).isEmpty()
    }

    @Test
    fun `busy time of the selected calendars inside the window`() = runTest {
        grant()
        provider.calendars = listOf(CalendarRow(1, "Work"), CalendarRow(2, "Holidays", visible = false), CalendarRow(3, "Home"))
        provider.instances = listOf(
            meeting("2026-10-07T10:00", "2026-10-07T11:00"),
            meeting("2026-10-07T10:30", "2026-10-07T11:30", calendarId = 3),
            meeting("2026-10-07T12:00", "2026-10-07T13:00", calendarId = 2),
            meeting("2026-10-07T14:00", "2026-10-07T15:00", availability = Events.AVAILABILITY_TENTATIVE),
        )
        val (_, source) = source(this)

        val all = source.busy(window.first, window.second)
        assertThat(all.calendarAvailable).isTrue()
        assertThat(all.intervals).containsExactly(
            TimeInterval(local("2026-10-07T10:00"), local("2026-10-07T11:30")),
            TimeInterval(local("2026-10-07T14:00"), local("2026-10-07T15:00")),
        ).inOrder()

        store.updateData { it.copy(selectedCalendars = setOf("com.google/me@example.com/cal-2"), tentativeIsBusy = false) }
        val holidaysOnly = source.busy(window.first, window.second)
        assertThat(holidaysOnly.intervals).containsExactly(TimeInterval(local("2026-10-07T12:00"), local("2026-10-07T13:00")))
    }

    @Test
    fun `instances are queried with a day of margin for all-day events`() = runTest {
        grant()
        provider.calendars = listOf(CalendarRow(1, "Work"))
        val (_, source) = source(this)

        source.busy(window.first, window.second)

        val uri = provider.instanceQueries.single()
        assertThat(uri.pathSegments.take(2)).containsExactly("instances", "when").inOrder()
        assertThat(uri.pathSegments[2].toLong()).isEqualTo(window.first.minus(Duration.ofDays(1)).toEpochMilli())
        assertThat(uri.pathSegments[3].toLong()).isEqualTo(window.second.plus(Duration.ofDays(1)).toEpochMilli())
    }

    @Test
    fun `with the permission the calendar is available even when empty`() = runTest {
        grant()
        val (_, source) = source(this)

        assertThat(source.busy(window.first, window.second)).isEqualTo(BusyTime(emptyList(), calendarAvailable = true))
        assertThat(source.busy(Instant.EPOCH, Instant.EPOCH)).isEqualTo(BusyTime(emptyList(), calendarAvailable = true))
    }

    @Test
    fun `a failing provider never breaks capacity`() = runTest {
        grant()
        provider.calendars = listOf(CalendarRow(1, "Work"))
        provider.failInstances = true
        val (_, source) = source(this)

        assertThat(source.busy(window.first, window.second)).isEqualTo(BusyTime.NONE)
    }

    @Test
    fun `calendars for the settings screen are sorted by account and name`() = runTest {
        grant()
        provider.calendars = listOf(
            CalendarRow(1, "Work", accountName = "b@example.com"),
            CalendarRow(2, "personal", accountName = "a@example.com"),
            CalendarRow(3, "Birthdays", accountName = "a@example.com", syncId = null, name = null, visible = false),
        )
        val (repository, _) = source(this)

        val calendars = repository.calendars()
        assertThat(calendars.map { it.displayName }).containsExactly("Birthdays", "personal", "Work").inOrder()
        assertThat(calendars.first().key).isEqualTo("com.google/a@example.com/#3")
        assertThat(calendars.first().visible).isFalse()
    }

    @Test
    fun `changes come from the provider and from the calendar settings, debounced`() = runTest {
        grant()
        val (_, source) = source(this)
        var changes = 0
        backgroundScope.launch { source.changes().collect { changes++ } }
        runCurrent()

        repeat(3) { app.contentResolver.notifyChange(CalendarContract.CONTENT_URI, null) }
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(changes).isEqualTo(1)

        store.updateData { it.copy(tentativeIsBusy = false) }
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(changes).isEqualTo(2)

        store.updateData { it.copy(bufferPercent = 10) }
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(changes).isEqualTo(2)
    }
}
