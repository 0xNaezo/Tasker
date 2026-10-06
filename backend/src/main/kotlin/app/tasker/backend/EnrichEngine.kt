package app.tasker.backend

import app.tasker.core.ai.claude.ClaudeRouteRunner
import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.RouteResult

/** Runs `/v1/enrich` against a model. Blocking; never throws for provider problems. Tests use a fake. */
fun interface EnrichEngine {
    fun enrich(request: EnrichRequest): RouteResult<EnrichResponse>
}

/** Production engine: the shared Claude runner with the route settings from the environment. */
class ClaudeEnrichEngine(private val runner: ClaudeRouteRunner) : EnrichEngine {
    override fun enrich(request: EnrichRequest): RouteResult<EnrichResponse> = runner.enrich(request)
}
