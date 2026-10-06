package app.tasker.feature.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tasker.core.ai.AiController
import app.tasker.core.ai.AiState
import app.tasker.core.ai.ConnectionCheck
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.model.AiMode
import app.tasker.core.ui.R as UiR
import app.tasker.core.ui.message.Messenger
import app.tasker.core.ui.text.UiText
import app.tasker.core.ui.util.attempt
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Why a key was not saved. */
enum class KeyError { INVALID, NOT_STORED }

/** AI help (SET-3, tech plan §17.1): nothing is sent before [giveConsent]; one switch turns everything off. */
@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val ai: AiController,
    private val messenger: Messenger,
) : ViewModel() {
    /** Null until read. */
    val state: StateFlow<AiState?> = ai.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val checkingFlow = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = checkingFlow.asStateFlow()

    fun giveConsent(mode: AiMode) = launch { ai.giveConsent(mode) }

    fun setEnabled(enabled: Boolean) = launch { ai.setEnabled(enabled) }

    fun dismissConsentPrompt() = launch { ai.dismissConsentPrompt() }

    /** Saves the key; [onResult] gets null on success. */
    fun saveKey(key: String, onResult: (KeyError?) -> Unit) {
        viewModelScope.launch {
            val result = attempt { ai.saveApiKey(key) }
            onResult(
                when (result.exceptionOrNull()) {
                    null -> null
                    is IllegalArgumentException -> KeyError.INVALID
                    else -> KeyError.NOT_STORED
                },
            )
        }
    }

    fun clearKey() = launch { ai.clearApiKey() }

    fun checkConnection() {
        if (checkingFlow.value) return
        checkingFlow.value = true
        viewModelScope.launch {
            val text = attempt { ai.checkConnection() }.getOrNull().let(::checkText)
            checkingFlow.value = false
            messenger.info(UiText.Res(text))
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            attempt { block() }.onFailure {
                Log.w(TAG, "AI settings change failed", it)
                messenger.info(UiText.Res(UiR.string.error_generic))
            }
        }
    }

    private fun checkText(check: ConnectionCheck?): Int = when (check) {
        ConnectionCheck.Ok -> R.string.settings_ai_check_ok
        is ConnectionCheck.Failed -> when (check.kind) {
            FailureKind.AUTH -> R.string.settings_ai_check_auth
            FailureKind.NETWORK -> R.string.settings_ai_check_network
            FailureKind.RATE_LIMIT, FailureKind.OVERLOADED -> R.string.settings_ai_check_busy
            else -> R.string.settings_ai_check_failed
        }
        ConnectionCheck.Refused, null -> R.string.settings_ai_check_failed
    }

    private companion object {
        const val TAG = "AiSettings"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
