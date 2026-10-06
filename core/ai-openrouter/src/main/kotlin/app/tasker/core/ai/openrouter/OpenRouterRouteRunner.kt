package app.tasker.core.ai.openrouter

import app.tasker.core.ai.contract.AiJson
import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.EnrichRoute
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.RouteContract
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.ai.contract.RouteSettings
import app.tasker.core.ai.contract.RouteUsage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Runs the AI routes of `core:ai-contract` through OpenRouter's chat completions API (ADR 0011): in the backend proxy
 * with the service key, in the app's direct mode with the user's own key, and in the quality eval.
 *
 * Every request:
 * - names the model by its OpenRouter id and sets the reasoning effort explicitly (`reasoning.effort`), because the
 *   default effort of Claude Opus 5.5 is `medium`; the reasoning stays out of the answer (`reasoning.exclude`) and is
 *   billed either way;
 * - asks for structured output with the route's JSON Schema (`response_format`, strict), never forced tool calls;
 * - accepts only endpoints that support every parameter sent (`provider.require_parameters`) and, unless the route
 *   settings turn it off, that keep no data (`provider.zdr`);
 * - puts the stable system prompt first with a `cache_control` breakpoint, and the variable data in the user turn.
 *
 * Answers are read by finish reason first (`content_filter`, `length`, `error`), then the content is parsed against the
 * route schema and checked by the contract. A failure nothing was billed for (408, 429, 5xx, no connection, timeout) is
 * retried [OpenRouterOptions.maxRetries] times with exponential backoff. The runner never throws for API or network
 * problems, it returns [RouteResult.Failed]; neither the key nor the task text ends up in a result.
 */
class OpenRouterRouteRunner(
    private val http: HttpClient,
    private val apiKey: String,
    private val settings: RouteSettings = EnrichRoute.defaultSettings,
    private val options: OpenRouterOptions = OpenRouterOptions(),
) {
    init {
        // The key goes into an HTTP header: OkHttp rejects anything but printable ASCII there.
        require(apiKey.isNotEmpty() && apiKey.all { it in HEADER_SAFE }) { "The API key must be printable ASCII without spaces" }
    }

    /** `/v1/enrich` with the settings this runner was created with. */
    suspend fun enrich(request: EnrichRequest): RouteResult<EnrichResponse> = run(EnrichRoute, request, settings)

    /** Runs any route contract; future routes (§17.3) reuse this unchanged. */
    suspend fun <Req, Res> run(
        contract: RouteContract<Req, Res>,
        request: Req,
        settings: RouteSettings = contract.route.defaultSettings,
    ): RouteResult<Res> {
        val problems = contract.requestProblems(request)
        if (problems.isNotEmpty()) {
            return RouteResult.Failed(FailureKind.BAD_REQUEST, detail = "invalid request fields: ${problems.joinToString()}")
        }
        val body = requestBody(contract, request, settings).toString()
        var result = interpret(contract, request, settings.model, send(body))
        var retries = 0
        while (result.isUnbilledRetryable() && retries < options.maxRetries) {
            delay(options.retryDelay * (1 shl retries))
            retries++
            result = interpret(contract, request, settings.model, send(body))
        }
        return result
    }

    private fun RouteResult<*>.isUnbilledRetryable(): Boolean = this is RouteResult.Failed && kind.retryable && usage == null

    private suspend fun send(body: String): Reply = try {
        val response = http.post(options.baseUrl.trimEnd('/') + OpenRouter.CHAT_COMPLETIONS_PATH) {
            expectSuccess = false
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            accept(ContentType.Application.Json)
            setBody(TextContent(body, ContentType.Application.Json))
        }
        Reply.Answered(response.status.value, response.bodyAsText())
    } catch (e: CancellationException) {
        // A request timeout can surface as a cancellation: only a cancelled caller is rethrown here.
        currentCoroutineContext().ensureActive()
        Reply.Unanswered(e.javaClass.simpleName)
    } catch (e: IOException) {
        Reply.Unanswered(e.javaClass.simpleName)
    } catch (e: IllegalStateException) {
        // The engine reports some connection failures this way; never with request content.
        Reply.Unanswered(e.javaClass.simpleName)
    }

    private sealed interface Reply {
        class Answered(val status: Int, val body: String) : Reply

        class Unanswered(val detail: String) : Reply
    }

    private fun <Req, Res> interpret(
        contract: RouteContract<Req, Res>,
        request: Req,
        requestedModel: String,
        reply: Reply,
    ): RouteResult<Res> = when (reply) {
        is Reply.Unanswered -> RouteResult.Failed(FailureKind.NETWORK, detail = reply.detail)
        is Reply.Answered -> if (reply.status in HTTP_SUCCESS) {
            answered(contract, request, requestedModel, reply.body)
        } else {
            rejected(reply.status, reply.body)
        }
    }

    private fun rejected(status: Int, body: String): RouteResult<Nothing> {
        val error = decode(ErrorResponse.serializer(), body)?.error
        // 403 with moderation reasons: OpenRouter's own moderation flagged the input.
        if (status == HTTP_FORBIDDEN && error?.metadata?.containsKey("reasons") == true) {
            return RouteResult.Refused(category = MODERATION)
        }
        return RouteResult.Failed(kindOfStatus(status), detail = "http_$status")
    }

    private fun <Req, Res> answered(
        contract: RouteContract<Req, Res>,
        request: Req,
        requestedModel: String,
        body: String,
    ): RouteResult<Res> {
        val completion = decode(ChatCompletion.serializer(), body)
            ?: return RouteResult.Failed(FailureKind.INVALID_OUTPUT, detail = "malformed response")
        val usage = completion.usage?.let { usageOf(it, completion.model ?: requestedModel) }
        val choice = completion.choices.firstOrNull()
            ?: return completion.error
                ?.let { RouteResult.Failed(kindOfError(it), usage, "error_${codeOf(it)}") }
                ?: RouteResult.Failed(FailureKind.INVALID_OUTPUT, usage, "no choices")
        val finishReason = choice.finishReason
        return when {
            finishReason == FINISH_CONTENT_FILTER || !choice.message?.refusal.isNullOrBlank() ->
                RouteResult.Refused(category = safeToken(choice.nativeFinishReason), usage = usage)
            finishReason == FINISH_LENGTH -> RouteResult.Failed(FailureKind.TRUNCATED, usage, "finish_reason=length")
            finishReason == FINISH_ERROR ->
                RouteResult.Failed(choice.error?.let(::kindOfError) ?: FailureKind.OVERLOADED, usage, "finish_reason=error")
            finishReason == null || finishReason == FINISH_STOP ->
                parse(contract, request, choice.message, usage ?: RouteUsage(completion.model ?: requestedModel, 0, 0))
            else -> RouteResult.Failed(FailureKind.INVALID_OUTPUT, usage, "finish_reason=${safeToken(finishReason)}")
        }
    }

    private fun <Req, Res> parse(
        contract: RouteContract<Req, Res>,
        request: Req,
        message: Message?,
        usage: RouteUsage,
    ): RouteResult<Res> {
        val text = message?.content?.let(::textOf)?.takeIf { it.isNotBlank() }
            ?: return RouteResult.Failed(FailureKind.INVALID_OUTPUT, usage, "no content")
        val raw = try {
            AiJson.modelOutput.decodeFromString(contract.responseSerializer, text)
        } catch (e: SerializationException) {
            return RouteResult.Failed(FailureKind.INVALID_OUTPUT, usage, e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            return RouteResult.Failed(FailureKind.INVALID_OUTPUT, usage, e.javaClass.simpleName)
        }
        return RouteResult.Success(contract.validate(request, raw), usage)
    }

    internal companion object {
        private val HTTP_SUCCESS = 200..299
        private const val HTTP_PAYMENT_REQUIRED = 402
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val HTTP_NO_ACCESS = setOf(401, HTTP_PAYMENT_REQUIRED, HTTP_FORBIDDEN)
        private val HTTP_REJECTED_REQUESTS = setOf(400, 404, 413, 422)
        private val HTTP_SERVER_ERRORS = 500..599

        private const val FINISH_STOP = "stop"
        private const val FINISH_LENGTH = "length"
        private const val FINISH_CONTENT_FILTER = "content_filter"
        private const val FINISH_ERROR = "error"
        const val MODERATION = "moderation"

        private val HEADER_SAFE = '!'..'~'
        private val SAFE_TOKEN = Regex("[a-z0-9_.-]{1,40}")
        private const val MICROS_PER_USD = 6

        /** The request body; keys come in a fixed order, so everything before the user turn is byte-identical. */
        fun <Req, Res> requestBody(contract: RouteContract<Req, Res>, request: Req, settings: RouteSettings): JsonObject =
            buildJsonObject {
                put("model", settings.model)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", contract.systemPrompt)
                                putJsonObject("cache_control") { put("type", "ephemeral") }
                            }
                        }
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", contract.userMessage(request))
                    }
                }
                put("max_tokens", settings.maxTokens)
                putJsonObject("reasoning") {
                    put("effort", settings.effort)
                    put("exclude", true)
                }
                putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", contract.route.name.lowercase())
                        put("strict", true)
                        put("schema", contract.outputSchema)
                    }
                }
                putJsonObject("provider") {
                    put("require_parameters", true)
                    // Without it the account's own data policy decides.
                    if (settings.zeroDataRetention) put("zdr", true)
                }
            }

        /** Maps HTTP errors of OpenRouter to failure kinds (see "Errors" in the OpenRouter API docs). */
        fun kindOfStatus(status: Int): FailureKind = when (status) {
            in HTTP_NO_ACCESS -> FailureKind.AUTH
            HTTP_REQUEST_TIMEOUT -> FailureKind.NETWORK
            HTTP_TOO_MANY_REQUESTS -> FailureKind.RATE_LIMIT
            in HTTP_REJECTED_REQUESTS -> FailureKind.BAD_REQUEST
            in HTTP_SERVER_ERRORS -> FailureKind.OVERLOADED
            else -> FailureKind.UNKNOWN
        }

        /** An error inside a successful answer: its code is an HTTP status; an upstream failure without one is retryable. */
        private fun kindOfError(error: ApiError): FailureKind = (error.code as? JsonPrimitive)?.intOrNull
            ?.let(::kindOfStatus)
            ?.takeUnless { it == FailureKind.UNKNOWN }
            ?: FailureKind.OVERLOADED

        private fun codeOf(error: ApiError): String = (error.code as? JsonPrimitive)?.intOrNull?.toString() ?: "unknown"

        /**
         * Usage of one answer. OpenRouter counts cached and cache-written tokens in `prompt_tokens`; [RouteUsage] keeps
         * them apart, so each token is priced once. The charged cost comes in USD and is kept in micro-USD, rounded up.
         */
        fun usageOf(usage: Usage, model: String): RouteUsage {
            val cached = usage.promptTokensDetails?.cachedTokens?.coerceAtLeast(0) ?: 0
            val written = usage.promptTokensDetails?.cacheWriteTokens?.coerceAtLeast(0) ?: 0
            return RouteUsage(
                model = model,
                inputTokens = (usage.promptTokens - cached - written).coerceAtLeast(0),
                outputTokens = usage.completionTokens.coerceAtLeast(0),
                cacheReadTokens = cached,
                cacheCreationTokens = written,
                costMicroUsd = usage.cost?.takeIf { it.isFinite() && it >= 0.0 }?.let { usd ->
                    BigDecimal.valueOf(usd).movePointRight(MICROS_PER_USD).setScale(0, RoundingMode.CEILING).toLong()
                },
            )
        }

        /** A string, or the text parts of a content array joined. */
        private fun textOf(content: JsonElement): String? = when (content) {
            is JsonPrimitive -> content.contentOrNull
            is JsonArray -> content.mapNotNull { part -> ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
                .joinToString("")
            else -> null
        }

        /** Provider-defined values go into results only when they look like machine tokens. */
        private fun safeToken(value: String?): String? = value?.lowercase()?.takeIf { SAFE_TOKEN.matches(it) }

        private fun <T> decode(serializer: KSerializer<T>, body: String): T? = try {
            OpenRouterJson.decodeFromString(serializer, body)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
