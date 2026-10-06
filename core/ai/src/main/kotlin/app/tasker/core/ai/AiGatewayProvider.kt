package app.tasker.core.ai

import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.model.AiMode
import app.tasker.core.model.AiSettings
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Singleton

/** Binding of the direct-mode gateway ([DirectAiGateway]). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DirectGateway

/** Binding of the proxy-mode gateway ([ProxyAiGateway]). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProxyGateway

/**
 * Picks the gateway from the settings and what the build offers (tech plan §17.2, §24.1). A mode chosen in a build of
 * another channel (e.g. data moved from the GitHub build to the Google Play build) counts as no mode: the user has to
 * choose again, because the data path differs. As an [AiGateway] itself, it routes every call to [active].
 *
 * Gateways are created lazily: a GitHub build never builds the proxy client and a Google Play build never builds the
 * Claude SDK client.
 */
@Singleton
class AiGatewayProvider @Inject constructor(
    private val environment: AiEnvironment,
    private val settings: SettingsRepository,
    private val keys: ApiKeyStore,
    @param:DirectGateway private val direct: Provider<AiGateway>,
    @param:ProxyGateway private val proxy: Provider<AiGateway>,
) : AiGateway {

    val availableModes: Set<AiMode> get() = environment.availableModes

    /** The chosen mode when this build offers it, otherwise null. */
    fun effectiveMode(ai: AiSettings): AiMode? = ai.mode?.takeIf { it in environment.availableModes }

    /**
     * The gateway for user data: a [DisabledAiGateway] unless the switch is on, consent was given, the chosen mode is
     * offered by this build and, in the direct mode, an API key is stored.
     */
    suspend fun active(): AiGateway {
        val ai = settings.current().ai
        if (!ai.isActive) return DisabledAiGateway(AiUnavailableReason.DISABLED)
        return forMode(effectiveMode(ai))
    }

    /** Whether [active] would send requests. */
    suspend fun isReady(): Boolean = active() !is DisabledAiGateway

    /** The gateway of the chosen mode regardless of the switch, for the connection check with a fixed text. */
    suspend fun configured(): AiGateway = forMode(effectiveMode(settings.current().ai))

    override suspend fun enrich(request: EnrichRequest): RouteResult<EnrichResponse> = active().enrich(request)

    private suspend fun forMode(mode: AiMode?): AiGateway = when (mode) {
        null -> DisabledAiGateway(AiUnavailableReason.NO_MODE)
        AiMode.DIRECT -> if (keys.get() != null) direct.get() else DisabledAiGateway(AiUnavailableReason.NO_API_KEY)
        AiMode.PROXY -> proxy.get()
    }
}
