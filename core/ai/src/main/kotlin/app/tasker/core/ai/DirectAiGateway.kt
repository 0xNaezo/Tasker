package app.tasker.core.ai

import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.EnrichRoute
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.ai.contract.RouteSettings
import app.tasker.core.ai.openrouter.OpenRouterRouteRunner
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.model.AiSettings
import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** The Ktor client of the direct mode's OpenRouter calls (OkHttp engine, no logging of bodies or headers). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OpenRouterHttpClient

/**
 * Direct mode of builds outside Google Play (tech plan §17.2, ADR 0011): the routes run on the device against
 * OpenRouter with the user's own key, through the same contract as the proxy ([OpenRouterRouteRunner]). The model comes
 * from the app settings ([AiSettings.model]). The key is read for every call, so a changed or removed key takes effect
 * at once.
 */
@Singleton
class DirectAiGateway @Inject constructor(
    private val keys: ApiKeyStore,
    private val settings: SettingsRepository,
    @param:OpenRouterHttpClient private val http: HttpClient,
) : AiGateway {

    override suspend fun enrich(request: EnrichRequest): RouteResult<EnrichResponse> {
        val apiKey = keys.get() ?: return RouteResult.Failed(FailureKind.AUTH, detail = AiUnavailableReason.NO_API_KEY.code)
        val routeSettings = routeSettingsFor(settings.current().ai.model)
        return OpenRouterRouteRunner(http, apiKey, routeSettings).enrich(request)
    }

    internal companion object {
        /**
         * `/v1/enrich` settings (§17.4) for the model chosen in the app settings. OpenRouter ids name the provider
         * (`anthropic/…`); anything else, such as an id saved before the switch to OpenRouter, means the default model.
         */
        fun routeSettingsFor(model: String): RouteSettings {
            val id = model.trim().takeIf { '/' in it } ?: AiSettings.DEFAULT_MODEL
            return EnrichRoute.defaultSettings.copy(model = id)
        }
    }
}
