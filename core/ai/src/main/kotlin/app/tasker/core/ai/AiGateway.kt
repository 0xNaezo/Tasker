package app.tasker.core.ai

import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.RouteResult

/**
 * Domain-facing access to the AI routes (tech plan §17.2): "disabled", "direct" (the user's own API key) and
 * "proxy" (Google Play builds) implement it; a model on the device may follow.
 *
 * Implementations never throw for API, network or configuration problems: they return [RouteResult.Failed] or
 * [RouteResult.Refused], and the affected fields simply stay empty (§17.1, §17.4). They do their network work on a
 * background dispatcher, so callers may use any context. Results are already checked by the route contract
 * (`core:ai-contract`).
 */
interface AiGateway {
    /** `/v1/enrich`: S/M/L estimate with a confidence and dates the rules missed (AI-1, CAP-7, §17.3). */
    suspend fun enrich(request: EnrichRequest): RouteResult<EnrichResponse>
}

/** Why AI cannot serve requests right now; [code] is the diagnostic of the failure it produces. */
enum class AiUnavailableReason(val code: String) {
    /** The switch is off or there is no consent (SET-3). */
    DISABLED("ai_disabled"),

    /** No mode chosen, or the chosen mode is not offered by this build (§24.1). */
    NO_MODE("ai_no_mode"),

    /** Direct mode without the user's API key. */
    NO_API_KEY("ai_no_api_key"),
}

/**
 * The gateway while AI cannot run: nothing is sent, every call fails at once with a non-retryable
 * [FailureKind.AUTH] ("not allowed to use AI now"). The enrichment queue keeps its tasks pending in that case.
 */
class DisabledAiGateway(val reason: AiUnavailableReason) : AiGateway {
    override suspend fun enrich(request: EnrichRequest): RouteResult<EnrichResponse> =
        RouteResult.Failed(FailureKind.AUTH, detail = reason.code)

    override fun toString(): String = "DisabledAiGateway(${reason.code})"
}
