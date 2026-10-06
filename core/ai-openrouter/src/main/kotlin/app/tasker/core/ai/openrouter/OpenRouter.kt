package app.tasker.core.ai.openrouter

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The OpenRouter API (ADR 0011). */
object OpenRouter {
    const val BASE_URL = "https://openrouter.ai/api/v1"
    internal const val CHAT_COMPLETIONS_PATH = "/chat/completions"

    /** One attempt: enrich runs at low effort and normally answers within seconds, but reasoning can take longer. */
    val REQUEST_TIMEOUT: Duration = 60.seconds
    val CONNECT_TIMEOUT: Duration = 15.seconds
}

/**
 * How [OpenRouterRouteRunner] reaches OpenRouter.
 *
 * @property baseUrl the API root; https only, since the request carries the API key and the task text.
 * @property maxRetries extra attempts after a failure nothing was billed for: 408, 429, 5xx, no connection, timeout.
 * @property retryDelay the pause before the first retry; it doubles with every further retry.
 */
data class OpenRouterOptions(
    val baseUrl: String = OpenRouter.BASE_URL,
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val retryDelay: Duration = DEFAULT_RETRY_DELAY,
) {
    init {
        require(baseUrl.startsWith("https://")) { "The OpenRouter base URL must start with https://" }
        require(maxRetries in 0..MAX_RETRIES) { "maxRetries must be in 0..$MAX_RETRIES" }
        require(!retryDelay.isNegative()) { "retryDelay must not be negative" }
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 2
        val DEFAULT_RETRY_DELAY: Duration = 1.seconds
        private const val MAX_RETRIES = 5
    }
}

/**
 * Timeouts of the client given to [OpenRouterRouteRunner]: [requestTimeout] bounds one attempt. That client must not
 * install a logging plugin: neither the API key nor task texts may reach logs (tech plan §21).
 */
fun HttpClientConfig<*>.openRouterTimeouts(requestTimeout: Duration = OpenRouter.REQUEST_TIMEOUT) {
    install(HttpTimeout) {
        connectTimeoutMillis = OpenRouter.CONNECT_TIMEOUT.inWholeMilliseconds
        socketTimeoutMillis = requestTimeout.inWholeMilliseconds
        requestTimeoutMillis = requestTimeout.inWholeMilliseconds
    }
}
