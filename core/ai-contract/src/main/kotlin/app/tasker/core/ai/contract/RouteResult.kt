package app.tasker.core.ai.contract

import kotlinx.serialization.Serializable

/**
 * Outcome of one AI route call, shared by the direct mode, the proxy client and the backend.
 * Whatever goes wrong, the affected fields simply stay empty in the app (tech plan §17.1, §17.4).
 */
sealed interface RouteResult<out T> {
    /** Token usage of the call when a response arrived (billing applies even to refusals and truncations). */
    val usage: RouteUsage?

    data class Success<T>(val value: T, override val usage: RouteUsage) : RouteResult<T>

    /**
     * The model or a safety filter declined (`finish_reason: "content_filter"`, a `refusal` message or OpenRouter
     * moderation). [category] is informational (e.g. the provider's own finish reason, "refusal"); may be null.
     */
    data class Refused(val category: String?, override val usage: RouteUsage? = null) : RouteResult<Nothing>

    /** The call failed; [detail] is a short diagnostic that never contains user text. */
    data class Failed(
        val kind: FailureKind,
        override val usage: RouteUsage? = null,
        val detail: String? = null,
    ) : RouteResult<Nothing>
}

enum class FailureKind(
    /** Worth retrying later with backoff (the app's queue keeps the task PENDING). */
    val retryable: Boolean,
) {
    /** Invalid, revoked or unfunded API key (no credits), or no permission (401, 402, 403). */
    AUTH(retryable = false),

    /** Provider rate limit (429). */
    RATE_LIMIT(retryable = true),

    /** Provider overloaded or failing (5xx, or an upstream error reported in the answer). */
    OVERLOADED(retryable = true),

    /** No connection, timeout or interrupted transfer. */
    NETWORK(retryable = true),

    /** The request was rejected as invalid (400, 404, 413, 422) or failed local validation. */
    BAD_REQUEST(retryable = false),

    /** The answer was not valid JSON for the route schema, or the finish reason was unexpected. */
    INVALID_OUTPUT(retryable = false),

    /** `finish_reason: "length"`: reasoning plus answer did not fit into `max_tokens`. */
    TRUNCATED(retryable = false),

    UNKNOWN(retryable = false),
}

/**
 * Token usage of one call. [inputTokens] are the prompt tokens neither read from nor written to the prompt cache, so
 * that every token is priced once. [costMicroUsd] is what the provider charged, when it reports it (OpenRouter does);
 * otherwise [ModelPricing] estimates the cost from the tokens.
 */
@Serializable
data class RouteUsage(
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val costMicroUsd: Long? = null,
)
