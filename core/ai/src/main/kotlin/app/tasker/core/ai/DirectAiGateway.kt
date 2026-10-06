package app.tasker.core.ai

import app.tasker.core.ai.claude.ClaudeRouteRunner
import app.tasker.core.ai.claude.createClient
import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.EnrichRoute
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.ai.contract.RouteSettings
import app.tasker.core.data.di.IoDispatcher
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.model.AiSettings
import com.anthropic.client.AnthropicClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible

/**
 * Direct mode of builds outside Google Play (tech plan §17.2): the routes run on the device against the Claude API
 * with the user's own key, through the same contract as the proxy ([ClaudeRouteRunner]). The model comes from the
 * app settings ([AiSettings.model]). One SDK client is kept per key and replaced when the key changes; the blocking
 * SDK call runs on the IO dispatcher and is interrupted when the caller is cancelled.
 */
@Singleton
class DirectAiGateway internal constructor(
    private val keys: ApiKeyStore,
    private val settings: SettingsRepository,
    private val io: CoroutineDispatcher,
    private val clientFactory: (apiKey: String) -> AnthropicClient,
) : AiGateway {

    @Inject
    constructor(
        keys: ApiKeyStore,
        settings: SettingsRepository,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(keys, settings, io, { apiKey -> createClient(apiKey) })

    private var cached: CachedClient? = null

    override suspend fun enrich(request: EnrichRequest): RouteResult<EnrichResponse> {
        val apiKey = keys.get()
        if (apiKey == null) {
            release()
            return RouteResult.Failed(FailureKind.AUTH, detail = AiUnavailableReason.NO_API_KEY.code)
        }
        val routeSettings = routeSettingsFor(settings.current().ai.model)
        return runInterruptible(io) { ClaudeRouteRunner(clientFor(apiKey), routeSettings).enrich(request) }
    }

    private fun clientFor(apiKey: String): AnthropicClient = synchronized(this) {
        cached?.takeIf { it.apiKey == apiKey }?.let { return it.client }
        cached?.client?.close()
        clientFactory(apiKey).also { cached = CachedClient(apiKey, it) }
    }

    private fun release() = synchronized(this) {
        cached?.client?.close()
        cached = null
    }

    /** Not a data class: the key must never end up in a string. */
    private class CachedClient(val apiKey: String, val client: AnthropicClient)

    internal companion object {
        /** Models that accept server-side refusal fallbacks (`fallbacks: "default"`, Claude API only). */
        private val FALLBACK_MODELS = listOf("claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-5-5")

        /** `/v1/enrich` settings (§17.4) for the model chosen in the app settings. */
        fun routeSettingsFor(model: String): RouteSettings {
            val id = model.trim().ifEmpty { AiSettings.DEFAULT_MODEL }
            return EnrichRoute.defaultSettings.copy(model = id, fallbacks = supportsServerFallbacks(id))
        }

        fun supportsServerFallbacks(model: String): Boolean = FALLBACK_MODELS.any { model == it || model.startsWith("$it-") }
    }
}
