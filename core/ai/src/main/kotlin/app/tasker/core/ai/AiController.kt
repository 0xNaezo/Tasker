package app.tasker.core.ai

import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.data.ai.AiCommands
import app.tasker.core.data.repository.TaskRepository
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AiMode
import app.tasker.core.model.EnrichState
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What the AI section of the settings shows (SET-3, tech plan §17.1). */
data class AiState(
    /** Switch position. */
    val enabled: Boolean,
    val hasConsent: Boolean,
    /** The chosen mode when this build offers it, otherwise null (choose again). */
    val mode: AiMode?,
    val availableModes: Set<AiMode>,
    /** A key is stored; it matters in the direct mode only. */
    val hasApiKey: Boolean,
    /** Tasks waiting for enrichment; approximate (tasks that are no longer active are not counted). */
    val pendingCount: Int,
    /** The user answered "not now" on the consent card, so the card is not shown again (§17.1). */
    val consentPromptDismissed: Boolean = false,
) {
    val isActive: Boolean get() = enabled && hasConsent && mode != null && (mode != AiMode.DIRECT || hasApiKey)
}

/** Result of [AiController.checkConnection]. */
sealed interface ConnectionCheck {
    data object Ok : ConnectionCheck

    /** [kind] tells what to fix: [FailureKind.AUTH] the key or access, [FailureKind.NETWORK] the connection, and so on. */
    data class Failed(val kind: FailureKind) : ConnectionCheck

    data object Refused : ConnectionCheck
}

/**
 * AI settings for the Settings screen (SET-3, tech plan §17.1, §17.2): consent, the switch, the mode and the user's
 * API key. Turning AI off cancels the queue; fields that AI already filled keep their AI mark (AI-4).
 */
@Singleton
class AiController @Inject constructor(
    private val settings: SettingsRepository,
    private val keys: ApiKeyStore,
    private val gateways: AiGatewayProvider,
    private val commands: AiCommands,
    private val scheduler: EnrichmentScheduler,
    private val requests: EnrichRequestFactory,
    private val tasks: TaskRepository,
    private val clock: DayClock,
) {
    val state: Flow<AiState> = combine(
        settings.settings.map { it.ai }.distinctUntilChanged(),
        keys.hasKey,
        tasks.observeActive().map { list -> list.count { it.enrichState == EnrichState.PENDING } }.distinctUntilChanged(),
    ) { ai, hasKey, pending ->
        AiState(
            enabled = ai.enabled,
            hasConsent = ai.hasConsent,
            mode = gateways.effectiveMode(ai),
            availableModes = gateways.availableModes,
            hasApiKey = hasKey,
            pendingCount = pending,
            consentPromptDismissed = ai.consentPromptDismissed,
        )
    }.distinctUntilChanged()

    /**
     * Records the consent time, turns AI on with [mode] and starts the queue. Consent covers what §17.3 lists; giving it
     * again (e.g. for another mode) records the new time.
     *
     * @throws IllegalArgumentException when this build does not offer [mode].
     */
    suspend fun giveConsent(mode: AiMode) {
        require(mode in gateways.availableModes) { "AI mode $mode is not available in this build" }
        val now = clock.now().toEpochMilli()
        settings.update { it.copy(ai = it.ai.copy(enabled = true, consentAtEpochMillis = now, mode = mode)) }
        enqueueIfReady()
    }

    /**
     * The switch (SET-3). Off: the queue is cancelled together with a run in progress; filled fields keep their AI mark.
     * On without consent only moves the switch: AI stays inactive until [giveConsent].
     */
    suspend fun setEnabled(enabled: Boolean) {
        settings.update { it.copy(ai = it.ai.copy(enabled = enabled)) }
        if (enabled) {
            enqueueIfReady()
        } else {
            commands.cancelQueue()
            scheduler.cancel()
        }
    }

    /** "Not now" on the consent card. */
    suspend fun dismissConsentPrompt() {
        settings.update { it.copy(ai = it.ai.copy(consentPromptDismissed = true)) }
    }

    /**
     * Stores the user's API key (direct mode) and resumes the queue.
     *
     * @throws IllegalArgumentException when the key is blank or contains whitespace (see [ApiKeyStore.normalize]).
     * @throws IOException when the key cannot be stored.
     */
    @Throws(IOException::class)
    suspend fun saveApiKey(key: String) {
        keys.save(key)
        enqueueIfReady()
    }

    /** Deletes the key (§21). Pending tasks wait for a new key or for AI to be turned off. */
    suspend fun clearApiKey() {
        keys.clear()
        // A run in progress would finish its current request with the old key; the proxy mode does not use the key.
        if (gateways.effectiveMode(settings.current().ai) == AiMode.DIRECT) scheduler.cancel()
    }

    /**
     * Sends one tiny `/v1/enrich` request with a fixed harmless text through the chosen mode (the switch does not need
     * to be on; no user data is sent). Without a mode, or in the direct mode without a key, it fails with
     * [FailureKind.AUTH] at once.
     */
    suspend fun checkConnection(): ConnectionCheck = when (val result = gateways.configured().enrich(requests.probe())) {
        is RouteResult.Success -> ConnectionCheck.Ok
        is RouteResult.Refused -> ConnectionCheck.Refused
        is RouteResult.Failed -> ConnectionCheck.Failed(result.kind)
    }

    private suspend fun enqueueIfReady() {
        if (gateways.isReady()) scheduler.enqueue()
    }
}
