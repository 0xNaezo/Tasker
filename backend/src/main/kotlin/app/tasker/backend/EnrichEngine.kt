package app.tasker.backend

import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.ai.openrouter.OpenRouterRouteRunner

/** Runs `/v1/enrich` against a model. Never throws for provider problems. Tests use a fake. */
interface EnrichEngine {
    suspend fun enrich(request: EnrichRequest): RouteResult<EnrichResponse>
}

/** Production engine: the shared OpenRouter runner with the route settings from the environment. */
class OpenRouterEnrichEngine(private val runner: OpenRouterRouteRunner) : EnrichEngine {
    override suspend fun enrich(request: EnrichRequest): RouteResult<EnrichResponse> = runner.enrich(request)
}
