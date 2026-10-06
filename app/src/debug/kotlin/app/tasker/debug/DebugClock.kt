package app.tasker.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import app.tasker.core.domain.time.ClockShift
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The managed clock of debug builds (tech plan §22.1, §24.1): the app's time runs ahead of the device's by a stored
 * offset, so "N days later" scenarios run against the real app. Alarms are still set in device time.
 */
@Singleton
class DebugClockShift @Inject constructor(@param:ApplicationContext context: Context) : ClockShift {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var minutes: Long = prefs.getLong(KEY_MINUTES, 0)

    override fun offset(): Duration = Duration.ofMinutes(minutes)

    fun shiftBy(delta: Duration) = set(minutes + delta.toMinutes())

    fun reset() = set(0)

    private fun set(value: Long) {
        minutes = value
        prefs.edit(commit = true) { putLong(KEY_MINUTES, value) }
    }

    private companion object {
        const val PREFS = "debug_clock"
        const val KEY_MINUTES = "offset_minutes"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugClockModule {
    @Binds
    abstract fun clockShift(shift: DebugClockShift): ClockShift
}

/**
 * Moves the managed clock from adb; only the shell may send it (the receiver requires `DUMP`):
 *
 * ```
 * adb shell am broadcast -a app.tasker.debug.SHIFT_CLOCK --ei days 3 --ei hours 2 -p io.github.oxnaezo.tasks
 * adb shell am broadcast -a app.tasker.debug.SHIFT_CLOCK --ez reset true -p io.github.oxnaezo.tasks
 * ```
 *
 * The shift adds up. Restart the app afterwards so the catch-up pass runs as on a real return after a pause.
 */
@AndroidEntryPoint
class ClockShiftReceiver : BroadcastReceiver() {
    @Inject
    lateinit var shift: DebugClockShift

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_RESET, false)) {
            shift.reset()
        } else {
            val delta = Duration.ofDays(intent.getIntExtra(EXTRA_DAYS, 0).toLong())
                .plusHours(intent.getIntExtra(EXTRA_HOURS, 0).toLong())
                .plusMinutes(intent.getIntExtra(EXTRA_MINUTES, 0).toLong())
            shift.shiftBy(delta)
        }
        Log.i(TAG, "Managed clock offset: ${shift.offset()}")
    }

    private companion object {
        const val TAG = "DebugClock"
        const val EXTRA_DAYS = "days"
        const val EXTRA_HOURS = "hours"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_RESET = "reset"
    }
}
