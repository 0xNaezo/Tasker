package app.tasker.core.calendar.di

import app.tasker.core.calendar.CalendarBusyTimeSource
import app.tasker.core.domain.port.BusyTimeSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Fills the optional [BusyTimeSource] declared by the data layer (`DataBindingsModule`). */
@Module
@InstallIn(SingletonComponent::class)
abstract class CalendarModule {
    @Binds
    abstract fun busyTimeSource(source: CalendarBusyTimeSource): BusyTimeSource
}
