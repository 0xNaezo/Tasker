package app.tasker.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.tasker.core.data.effects.CommitListener
import app.tasker.core.data.settings.AppSettingsSerializer
import app.tasker.core.domain.capacity.CapacityCalculator
import app.tasker.core.domain.port.BusyTimeSource
import app.tasker.core.domain.time.ClockShift
import app.tasker.core.domain.time.DayClock
import app.tasker.core.domain.time.ShiftedTimeSource
import app.tasker.core.domain.time.SystemTimeSource
import app.tasker.core.domain.time.TimeSource
import app.tasker.core.model.AppSettings
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import java.util.Optional
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    /** A debug build may shift the app's clock for "N days later" scenarios (tech plan §22.1); release has no shift. */
    @Provides
    @Singleton
    fun timeSource(shift: Optional<ClockShift>): TimeSource =
        if (shift.isPresent) ShiftedTimeSource(SystemTimeSource(), shift.get()) else SystemTimeSource()

    @Provides
    @Singleton
    fun dayClock(source: TimeSource): DayClock = DayClock(source)

    @Provides
    fun capacityCalculator(clock: DayClock): CapacityCalculator = CapacityCalculator(clock)

    @Provides
    @Singleton
    fun settingsStore(@ApplicationContext context: Context): DataStore<AppSettings> = DataStoreFactory.create(
        serializer = AppSettingsSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
        produceFile = { context.dataStoreFile(SETTINGS_FILE) },
    )

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    const val SETTINGS_FILE = "settings.json"
}

/** Declarations for bindings that other modules may or may not contribute. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingsModule {
    /** Post-commit listeners from platform modules (reminders, widget, AI queue); empty by default. */
    @Multibinds
    abstract fun commitListeners(): Set<CommitListener>

    /** Bound by the calendar module; without it capacity is working hours minus buffer (PLN-3). */
    @BindsOptionalOf
    abstract fun busyTimeSource(): BusyTimeSource

    /** Bound only by debug builds (managed clock). */
    @BindsOptionalOf
    abstract fun clockShift(): ClockShift
}
