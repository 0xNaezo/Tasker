package app.tasker.core.scheduling

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bookkeeping of the scheduler that is not domain data: which day the morning plan was handled and when the deadline
 * of a task last changed. Losing it (backup restore, cleared data) only means a possible extra reminder.
 */
@Singleton
class SchedulerState @Inject constructor(@param:ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * The logical day whose morning plan already ran (sent or deliberately skipped). Before the first plan it is the day
     * before the first alarm, so plan times from before the app scheduled anything never count as missed.
     */
    var lastMorningPlanDay: LocalDate?
        get() = prefs.getString(LAST_PLAN_DAY, null)?.let(LocalDate::parse)
        set(value) = prefs.edit { if (value == null) remove(LAST_PLAN_DAY) else putString(LAST_PLAN_DAY, value.toString()) }

    /** When the deadline, the reminders or the status of each task last changed. */
    fun reminderChanges(): Map<String, Instant> = prefs.all.mapNotNull { (key, value) ->
        if (key.startsWith(CHANGED_PREFIX) && value is Long) key.removePrefix(CHANGED_PREFIX) to Instant.ofEpochMilli(value) else null
    }.toMap()

    fun markReminderChange(taskIds: Collection<String>, at: Instant) {
        if (taskIds.isEmpty()) return
        prefs.edit { taskIds.forEach { putLong(CHANGED_PREFIX + it, at.toEpochMilli()) } }
    }

    /** Forgets tasks that have no reminders any more. */
    fun retainReminderChanges(taskIds: Set<String>) {
        val stale = prefs.all.keys.filter { it.startsWith(CHANGED_PREFIX) && it.removePrefix(CHANGED_PREFIX) !in taskIds }
        if (stale.isNotEmpty()) prefs.edit { stale.forEach(::remove) }
    }

    private companion object {
        const val FILE = "tasker_scheduling"
        const val LAST_PLAN_DAY = "last_morning_plan_day"
        const val CHANGED_PREFIX = "reminders_changed:"
    }
}
