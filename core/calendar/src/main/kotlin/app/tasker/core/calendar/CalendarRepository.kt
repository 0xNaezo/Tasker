package app.tasker.core.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.os.Handler
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import android.util.Log
import androidx.core.content.ContextCompat
import app.tasker.core.data.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * Read-only access to the device calendars (CAL-1, PLN-3, tech plan §10.2). Every query runs on the IO dispatcher and
 * never throws: without READ_CALENDAR or on a provider error it returns `null` (internal queries) or an empty list.
 */
@Singleton
class CalendarRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val permissionState = MutableStateFlow(checkPermission())

    /**
     * Last known READ_CALENDAR state. It is refreshed by [hasPermission]; the settings and onboarding screens call it
     * after the permission dialog, so the busy-time source starts observing the calendar without a restart.
     */
    val permission: StateFlow<Boolean> = permissionState.asStateFlow()

    fun hasPermission(): Boolean = checkPermission().also { permissionState.value = it }

    /** Calendars of the device for the settings screen, grouped by account; empty without the permission. */
    suspend fun calendars(): List<DeviceCalendar> = calendarsOrNull().orEmpty()

    /** `null` when the calendars cannot be read (no permission or a provider error), unlike an empty list. */
    internal suspend fun calendarsOrNull(): List<DeviceCalendar>? = query {
        context.contentResolver.query(Calendars.CONTENT_URI, CALENDAR_PROJECTION, null, null, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toCalendar()) }
        }.orEmpty().sortedWith(BY_ACCOUNT_AND_NAME)
    }

    /** Event instances overlapping [from, to); the provider expands recurring events. `null` when they cannot be read. */
    internal suspend fun instances(from: Instant, to: Instant): List<EventInstance>? = query {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.toEpochMilli())
            ContentUris.appendId(it, to.toEpochMilli())
        }.build()
        context.contentResolver.query(uri, INSTANCE_PROJECTION, null, null, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toInstance()) }
        }.orEmpty()
    }

    /**
     * Emits on every change of calendars, events or instances. Registering an observer needs the permission, so
     * without it the flow stays silent; collect it again (or re-collect through [permission]) once it is granted.
     */
    internal fun providerChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(null as Handler?) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val registered = try {
            // The provider notifies its root URI; descendants cover calendars, events and instances.
            context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot observe the calendar provider", e)
            false
        }
        awaitClose { if (registered) context.contentResolver.unregisterContentObserver(observer) }
    }

    private suspend fun <T> query(block: () -> T): T? = withContext(io) {
        if (!hasPermission()) return@withContext null
        try {
            block()
        } catch (e: RuntimeException) {
            // SecurityException after a revoke, SQLiteException or a misbehaving provider: never break capacity.
            Log.w(TAG, "Calendar query failed", e)
            null
        }
    }

    private fun checkPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "CalendarRepository"

        val BY_ACCOUNT_AND_NAME: Comparator<DeviceCalendar> = compareBy(String.CASE_INSENSITIVE_ORDER, DeviceCalendar::accountName)
            .thenBy(String.CASE_INSENSITIVE_ORDER, DeviceCalendar::displayName)

        val CALENDAR_PROJECTION = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars._SYNC_ID,
            Calendars.NAME,
            Calendars.CALENDAR_COLOR,
            Calendars.VISIBLE,
        )

        val INSTANCE_PROJECTION = arrayOf(
            Instances.CALENDAR_ID,
            Instances.BEGIN,
            Instances.END,
            Instances.ALL_DAY,
            Instances.AVAILABILITY,
            Instances.STATUS,
            Instances.SELF_ATTENDEE_STATUS,
        )

        fun Cursor.toCalendar(): DeviceCalendar {
            val id = getLong(getColumnIndexOrThrow(Calendars._ID))
            val accountName = string(Calendars.ACCOUNT_NAME)
            val accountType = string(Calendars.ACCOUNT_TYPE)
            val name = string(Calendars.NAME)
            return DeviceCalendar(
                id = id,
                key = BusyTimeRules.selectionKey(accountType, accountName, string(Calendars._SYNC_ID), name, id),
                displayName = string(Calendars.CALENDAR_DISPLAY_NAME) ?: name ?: accountName.orEmpty(),
                accountName = accountName.orEmpty(),
                accountType = accountType.orEmpty(),
                color = int(Calendars.CALENDAR_COLOR) ?: 0,
                visible = int(Calendars.VISIBLE) != 0,
            )
        }

        fun Cursor.toInstance() = EventInstance(
            calendarId = getLong(getColumnIndexOrThrow(Instances.CALENDAR_ID)),
            begin = getLong(getColumnIndexOrThrow(Instances.BEGIN)),
            end = getLong(getColumnIndexOrThrow(Instances.END)),
            allDay = int(Instances.ALL_DAY) == 1,
            availability = int(Instances.AVAILABILITY),
            status = int(Instances.STATUS),
            selfAttendeeStatus = int(Instances.SELF_ATTENDEE_STATUS),
        )

        fun Cursor.string(column: String): String? {
            val index = getColumnIndex(column)
            return if (index < 0 || isNull(index)) null else getString(index)
        }

        fun Cursor.int(column: String): Int? {
            val index = getColumnIndex(column)
            return if (index < 0 || isNull(index)) null else getInt(index)
        }
    }
}
