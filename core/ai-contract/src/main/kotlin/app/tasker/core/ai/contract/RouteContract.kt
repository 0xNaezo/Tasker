package app.tasker.core.ai.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A typed AI route (tech plan §17.2): the server is not a generic LLM proxy. Each route owns its prompt,
 * the JSON schema of the answer and the semantic check of that answer. Both the direct mode (the app with
 * the user's own API key) and the backend proxy run the same contract.
 *
 * Adding a route from §17.3 (`/v1/classify`, `/v1/split`, ...) means: request/response DTOs, an object
 * implementing this interface, and an entry in [AiRoute].
 */
interface RouteContract<Req, Res> {
    val route: AiRoute

    /**
     * Stable instructions sent as the cached system prompt. Must be byte-identical across calls: it is the
     * prompt-cache prefix (tech plan §17.4), so no dates, ids or per-user data here.
     */
    val systemPrompt: String

    /** JSON Schema of the answer, sent as the strict `response_format`; also stable across calls. */
    val outputSchema: JsonObject

    val responseSerializer: KSerializer<Res>

    /** Field-level problems of [request]; empty when it may be sent. Never echoes user text. */
    fun requestProblems(request: Req): List<String>

    /** The variable part of the prompt (dates, task text), sent after the cached prefix. */
    fun userMessage(request: Req): String

    /** Semantic check of a schema-valid answer: drops whatever the route must not return. */
    fun validate(request: Req, raw: Res): Res
}

/** Route catalogue from tech plan §17.3 with per-route defaults from §17.4. */
enum class AiRoute(val path: String, val defaultSettings: RouteSettings) {
    ENRICH("/v1/enrich", RouteSettings(effort = RouteSettings.EFFORT_LOW, maxTokens = 4_096)),
    CLASSIFY("/v1/classify", RouteSettings(effort = RouteSettings.EFFORT_LOW, maxTokens = 4_096)),
    SPLIT("/v1/split", RouteSettings(effort = RouteSettings.EFFORT_MEDIUM, maxTokens = 8_192)),
    NEXT_STEP("/v1/next-step", RouteSettings(effort = RouteSettings.EFFORT_LOW, maxTokens = 4_096)),
    SIMILAR("/v1/similar", RouteSettings(effort = RouteSettings.EFFORT_LOW, maxTokens = 4_096)),
    SUMMARIZE_SOURCE("/v1/summarize-source", RouteSettings(effort = RouteSettings.EFFORT_MEDIUM, maxTokens = 8_192)),
}

/**
 * Model parameters of one route (tech plan §17.4), sent through OpenRouter (ADR 0011).
 *
 * - [model]: an OpenRouter model id, `anthropic/claude-opus-5.5` by default on every route; a cheaper model is the
 *   product owner's decision after the quality eval (§17.6).
 * - [effort]: the reasoning effort (`reasoning.effort`). Thinking cannot be disabled on Claude Opus 5.5 and its default
 *   effort is `medium`, so the value is always sent explicitly: `low` for extraction/classification, `medium` for
 *   split/summary.
 * - [maxTokens]: covers reasoning plus the JSON answer (reasoning counts towards the limit).
 * - [zeroDataRetention]: route only to endpoints that keep no prompts or answers (`provider.zdr`). Off means the
 *   OpenRouter account's own data policy decides.
 */
@Serializable
data class RouteSettings(
    val model: String = DEFAULT_MODEL,
    val effort: String = EFFORT_LOW,
    val maxTokens: Long = 4_096,
    val zeroDataRetention: Boolean = true,
) {
    init {
        require(model.isNotBlank()) { "model must not be blank" }
        require(effort in EFFORTS) { "effort must be one of $EFFORTS" }
        require(maxTokens in 1..MAX_OUTPUT_TOKENS) { "maxTokens must be in 1..$MAX_OUTPUT_TOKENS" }
    }

    companion object {
        const val DEFAULT_MODEL = "anthropic/claude-opus-5.5"
        const val EFFORT_LOW = "low"
        const val EFFORT_MEDIUM = "medium"
        const val EFFORT_HIGH = "high"
        const val EFFORT_XHIGH = "xhigh"
        const val EFFORT_MAX = "max"
        val EFFORTS: Set<String> = setOf(EFFORT_LOW, EFFORT_MEDIUM, EFFORT_HIGH, EFFORT_XHIGH, EFFORT_MAX)

        /** Output limit of the current Claude models (128K). */
        const val MAX_OUTPUT_TOKENS = 128_000L
    }
}
