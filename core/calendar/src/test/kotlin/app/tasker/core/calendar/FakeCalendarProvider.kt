package app.tasker.core.calendar

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances

/** A calendar row as the provider stores it. */
data class CalendarRow(
    val id: Long,
    val displayName: String,
    val accountName: String = "me@example.com",
    val accountType: String = "com.google",
    val syncId: String? = "cal-$id",
    val name: String? = displayName,
    val color: Int = 0xFF3366CC.toInt(),
    val visible: Boolean = true,
)

/** In-memory stand-in for the system calendar provider: serves `calendars` and `instances/when/{begin}/{end}`. */
class FakeCalendarProvider : ContentProvider() {
    var calendars: List<CalendarRow> = emptyList()
    var instances: List<EventInstance> = emptyList()
    var failInstances = false
    val instanceQueries = mutableListOf<Uri>()

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = when (uri.pathSegments.firstOrNull()) {
        "calendars" -> cursor(projection, calendars.map(::calendarValues))
        "instances" -> {
            instanceQueries += uri
            check(!failInstances) { "Provider is broken" }
            val begin = uri.pathSegments[2].toLong()
            val end = uri.pathSegments[3].toLong()
            cursor(projection, instances.filter { it.begin <= end && it.end >= begin }.map(::instanceValues))
        }
        else -> null
    }

    private fun cursor(projection: Array<out String>?, rows: List<Map<String, Any?>>): Cursor {
        val columns = checkNotNull(projection) { "The app always passes a projection" }
        return MatrixCursor(columns).apply { rows.forEach { row -> addRow(columns.map { row[it] }) } }
    }

    private fun calendarValues(row: CalendarRow): Map<String, Any?> = mapOf(
        Calendars._ID to row.id,
        Calendars.CALENDAR_DISPLAY_NAME to row.displayName,
        Calendars.ACCOUNT_NAME to row.accountName,
        Calendars.ACCOUNT_TYPE to row.accountType,
        Calendars._SYNC_ID to row.syncId,
        Calendars.NAME to row.name,
        Calendars.CALENDAR_COLOR to row.color,
        Calendars.VISIBLE to if (row.visible) 1 else 0,
    )

    private fun instanceValues(event: EventInstance): Map<String, Any?> = mapOf(
        Instances.CALENDAR_ID to event.calendarId,
        Instances.BEGIN to event.begin,
        Instances.END to event.end,
        Instances.ALL_DAY to if (event.allDay) 1 else 0,
        Instances.AVAILABILITY to event.availability,
        Instances.STATUS to event.status,
        Instances.SELF_ATTENDEE_STATUS to event.selfAttendeeStatus,
    )

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
