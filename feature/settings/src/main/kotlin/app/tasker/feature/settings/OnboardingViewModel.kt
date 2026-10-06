package app.tasker.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tasker.core.calendar.CalendarRepository
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.model.AppSettings
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.message.Messenger
import app.tasker.core.ui.text.UiText
import app.tasker.core.ui.util.attempt
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** First start (ONB-1): working hours, calendar access, first task. No account is involved (ONB-2). */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val messenger: Messenger,
    private val calendar: CalendarRepository,
) : ViewModel() {
    /** Null until the stored settings are read. */
    val state: StateFlow<AppSettings?> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { attempt { settings.update(transform) }.onFailure { error() } }
    }

    /** After the READ_CALENDAR dialog: busy time is read from now on, without a restart. */
    fun calendarPermissionChanged() {
        calendar.hasPermission()
    }

    /** The app leaves onboarding as soon as the flag is stored. */
    fun finish() = update { it.copy(onboardingDone = true) }

    private fun error() = messenger.info(UiText.Res(UiR.string.error_generic))

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
