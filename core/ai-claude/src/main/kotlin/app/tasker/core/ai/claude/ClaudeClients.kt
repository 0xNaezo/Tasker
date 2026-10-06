package app.tasker.core.ai.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import java.time.Duration

object ClaudeClientDefaults {
    /** Per-attempt HTTP timeout: enrich runs at low effort and normally answers within seconds. */
    val TIMEOUT: Duration = Duration.ofSeconds(60)

    /** The SDK retries 408/409/429/5xx with exponential backoff. */
    const val MAX_RETRIES = 2
}

/**
 * Creates an SDK client with an explicit API key: the user's own key in the app's direct mode, the
 * provider key from the environment on the backend. No environment variables are read here.
 * [baseUrl] points at another API endpoint (tests, gateways); null means the public Claude API.
 */
fun createClient(
    apiKey: String,
    baseUrl: String? = null,
    timeout: Duration = ClaudeClientDefaults.TIMEOUT,
    maxRetries: Int = ClaudeClientDefaults.MAX_RETRIES,
): AnthropicClient {
    require(apiKey.isNotBlank()) { "API key must not be blank" }
    val builder = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(timeout)
        .maxRetries(maxRetries)
    if (baseUrl != null) builder.baseUrl(baseUrl)
    return builder.build()
}
