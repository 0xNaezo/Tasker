package app.tasker.core.data.settings

import androidx.datastore.core.DataStore
import app.tasker.core.data.mapper.DataJson
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AppSettings
import app.tasker.core.model.SettingSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.jsonObject

/**
 * Settings with defaults (SET-1, SET-2). Every read keeps [DayClock.boundaryMinutes] in sync, so "today" follows the
 * day boundary setting. User changes record provenance USER for the changed keys (TUNE-4).
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val store: DataStore<AppSettings>,
    private val clock: DayClock,
) {
    val settings: Flow<AppSettings> = store.data
        .onEach(::syncClock)
        .distinctUntilChanged()

    suspend fun current(): AppSettings = store.data.first().also(::syncClock)

    /** Applies a user change; keys whose value changed are marked [SettingSource.USER]. */
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = store.updateData { old ->
        val new = transform(old)
        if (new == old) old else new.copy(provenance = new.provenance + changedKeys(old, new).associateWith { SettingSource.USER })
    }.also(::syncClock)

    /** Applies a change made by the app itself (self-tuning, L); keys are marked [SettingSource.AUTO]. */
    suspend fun updateAutomatically(transform: (AppSettings) -> AppSettings): AppSettings = store.updateData { old ->
        val new = transform(old)
        if (new == old) old else new.copy(provenance = new.provenance + changedKeys(old, new).associateWith { SettingSource.AUTO })
    }.also(::syncClock)

    /** Replaces all settings, e.g. on restore from a backup. */
    suspend fun replace(settings: AppSettings) {
        store.updateData { settings }
        syncClock(settings)
    }

    private fun syncClock(settings: AppSettings) {
        val boundary = settings.dayBoundaryMinutes
        if (boundary in 0 until DayClock.MINUTES_PER_DAY && clock.boundaryMinutes != boundary) {
            clock.boundaryMinutes = boundary
        }
    }

    companion object {
        /** Top-level setting keys whose value differs; provenance itself is not a setting. */
        fun changedKeys(old: AppSettings, new: AppSettings): Set<String> {
            val a = DataJson.encodeToJsonElement(AppSettings.serializer(), old).jsonObject
            val b = DataJson.encodeToJsonElement(AppSettings.serializer(), new).jsonObject
            return (a.keys + b.keys).filterTo(LinkedHashSet()) { it != PROVENANCE_KEY && a[it] != b[it] }
        }

        private const val PROVENANCE_KEY = "provenance"
    }
}
