package app.tasker.core.ai.openrouter

import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.EnrichRoute
import app.tasker.core.ai.contract.EstimateScale
import app.tasker.core.ai.contract.ExtractedFields
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.ai.contract.RouteSettings
import app.tasker.core.ai.contract.RouteUsage
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.net.ConnectException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenRouterRouteRunnerTest {

    private val api = FakeOpenRouter()
    private val runner = runner(maxRetries = 0)

    private val request = EnrichRequest(
        text = "подготовить квартальный отчёт до конца месяца",
        language = "ru",
        now = "2026-10-06T10:00",
        today = "2026-10-06",
        timeZone = "Europe/Kyiv",
        estimateScale = EstimateScale(15, 60, 180),
        extracted = ExtractedFields(),
    )

    private fun runner(maxRetries: Int, settings: RouteSettings = EnrichRoute.defaultSettings) = OpenRouterRouteRunner(
        http = HttpClient(api.engine) { openRouterTimeouts() },
        apiKey = "sk-or-v1-test",
        settings = settings,
        options = OpenRouterOptions(baseUrl = BASE_URL, maxRetries = maxRetries, retryDelay = Duration.ZERO),
    )

    @Test
    fun `request names the model, effort, schema and data policy`() {
        val body = OpenRouterRouteRunner.requestBody(EnrichRoute, request, EnrichRoute.defaultSettings)

        assertThat(body.getValue("model").jsonPrimitive.content).isEqualTo("anthropic/claude-opus-5.5")
        assertThat(body.getValue("max_tokens").jsonPrimitive.content).isEqualTo("4096")
        assertThat(body.getValue("reasoning")).isEqualTo(
            buildJsonObject {
                put("effort", "low")
                put("exclude", true)
            },
        )
        val format = body.getValue("response_format").jsonObject
        assertThat(format.getValue("type").jsonPrimitive.content).isEqualTo("json_schema")
        val schema = format.getValue("json_schema").jsonObject
        assertThat(schema.getValue("name").jsonPrimitive.content).isEqualTo("enrich")
        assertThat(schema.getValue("strict").jsonPrimitive.content).isEqualTo("true")
        assertThat(schema.getValue("schema")).isEqualTo(EnrichRoute.outputSchema)
        assertThat(body.getValue("provider")).isEqualTo(
            buildJsonObject {
                put("require_parameters", true)
                put("zdr", true)
            },
        )
        assertThat(body.keys).containsNoneOf("temperature", "tools", "tool_choice", "stream", "thinking")

        val (system, user) = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertThat(system.getValue("role").jsonPrimitive.content).isEqualTo("system")
        val part = system.getValue("content").jsonArray.single().jsonObject
        assertThat(part.getValue("text").jsonPrimitive.content).isEqualTo(EnrichRoute.systemPrompt)
        assertThat(part.getValue("cache_control")).isEqualTo(buildJsonObject { put("type", "ephemeral") })
        assertThat(user.getValue("role").jsonPrimitive.content).isEqualTo("user")
        assertThat(user.getValue("content").jsonPrimitive.content).contains("до конца месяца")
    }

    @Test
    fun `without zero data retention the account policy decides`() {
        val settings = RouteSettings(model = "anthropic/claude-haiku-4.5", zeroDataRetention = false)
        val body = OpenRouterRouteRunner.requestBody(EnrichRoute, request, settings)
        assertThat(body.getValue("provider")).isEqualTo(buildJsonObject { put("require_parameters", true) })
        assertThat(body.getValue("model").jsonPrimitive.content).isEqualTo("anthropic/claude-haiku-4.5")
    }

    @Test
    fun `wire request carries the key and a stable prefix`() = runTest {
        api.reply(200, completion(answer(estimate = "L")))
        runner.enrich(request)
        runner.enrich(request.copy(text = "позвонить маме", today = "2026-10-07", now = "2026-10-07T09:00"))

        val (first, second) = api.requests
        assertThat(first.url).isEqualTo("$BASE_URL/chat/completions")
        assertThat(first.authorization).isEqualTo("Bearer sk-or-v1-test")

        // Prompt caching: everything but the user turn is byte-identical across different tasks and days.
        val firstBody = Json.parseToJsonElement(first.body).jsonObject
        val secondBody = Json.parseToJsonElement(second.body).jsonObject
        assertThat(firstBody - "messages").isEqualTo(secondBody - "messages")
        val firstMessages = firstBody.getValue("messages").jsonArray
        val secondMessages = secondBody.getValue("messages").jsonArray
        assertThat(firstMessages[0].toString()).isEqualTo(secondMessages[0].toString())
        assertThat(firstMessages[1].toString()).isNotEqualTo(secondMessages[1].toString())
    }

    @Test
    fun `answer is parsed, validated and priced at the reported cost`() = runTest {
        api.reply(
            200,
            completion(
                answer(estimate = "L", confidence = 0.72, deadlineDate = "2026-10-31", deadlineFragment = "До конца месяца"),
                usage = """{"prompt_tokens":1630,"completion_tokens":95,"total_tokens":1725,"cost":0.0020001,""" +
                    """"prompt_tokens_details":{"cached_tokens":1450,"cache_write_tokens":0}}""",
            ),
        )
        val success = runner.enrich(request) as RouteResult.Success<EnrichResponse>

        assertThat(success.value).isEqualTo(
            EnrichResponse(
                estimate = "L",
                estimateConfidence = 0.72,
                deadlineDate = "2026-10-31",
                deadlineFragment = "до конца месяца",
            ),
        )
        assertThat(success.usage).isEqualTo(
            RouteUsage("anthropic/claude-opus-5.5", 180, 95, cacheReadTokens = 1450, costMicroUsd = 2_001),
        )
    }

    @Test
    fun `usage keeps cache reads and writes apart from uncached input`() {
        val usage = OpenRouterRouteRunner.usageOf(
            Usage(
                promptTokens = 5_000,
                completionTokens = 300,
                promptTokensDetails = PromptTokensDetails(cachedTokens = 1_000, cacheWriteTokens = 3_500),
                cost = 0.0000123,
            ),
            "anthropic/claude-opus-5.5",
        )
        assertThat(usage).isEqualTo(RouteUsage("anthropic/claude-opus-5.5", 500, 300, 1_000, 3_500, costMicroUsd = 13))
        assertThat(OpenRouterRouteRunner.usageOf(Usage(promptTokens = 10, cost = Double.NaN), "m").costMicroUsd).isNull()
    }

    @Test
    fun `semantic validation drops dates whose fragment is not in the text`() = runTest {
        api.reply(200, completion(answer(estimate = "M", deadlineDate = "2026-10-31", deadlineFragment = "end of month")))
        val value = (runner.enrich(request) as RouteResult.Success).value
        assertThat(value.estimate).isEqualTo("M")
        assertThat(value.deadlineDate).isNull()
    }

    @Test
    fun `content given as parts is read too`() = runTest {
        val parts = buildJsonObject {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", answer(estimate = "S"))
                }
            }
        }.getValue("content")
        api.reply(200, completion(content = null, contentElement = parts))
        assertThat((runner.enrich(request) as RouteResult.Success).value.estimate).isEqualTo("S")
    }

    @Test
    fun `refusals are reported with the provider's reason and usage`() = runTest {
        api.reply(200, completion(content = null, finishReason = "content_filter", nativeFinishReason = "refusal"))
        val refused = runner.enrich(request) as RouteResult.Refused
        assertThat(refused.category).isEqualTo("refusal")
        assertThat(refused.usage?.inputTokens).isEqualTo(100)

        api.reply(200, completion(content = null, refusal = "I can't help with that."))
        assertThat(runner.enrich(request)).isInstanceOf(RouteResult.Refused::class.java)

        // Some providers send an empty refusal next to a normal answer.
        api.reply(200, completion(answer(estimate = "S"), refusal = ""))
        assertThat(runner.enrich(request)).isInstanceOf(RouteResult.Success::class.java)
    }

    @Test
    fun `length stop is a truncation, not an answer`() = runTest {
        api.reply(200, completion("""{"estimate":"L","estim""", finishReason = "length", nativeFinishReason = "max_tokens"))
        val result = runner.enrich(request) as RouteResult.Failed
        assertThat(result.kind).isEqualTo(FailureKind.TRUNCATED)
        assertThat(result.usage).isNotNull()
    }

    @Test
    fun `malformed or missing content is invalid output`() = runTest {
        suspend fun kindFor(body: String): FailureKind {
            api.reply(200, body)
            return (runner.enrich(request) as RouteResult.Failed).kind
        }
        assertThat(kindFor(completion("not json"))).isEqualTo(FailureKind.INVALID_OUTPUT)
        assertThat(kindFor(completion(content = null))).isEqualTo(FailureKind.INVALID_OUTPUT)
        assertThat(kindFor(completion(answer(estimate = "M"), finishReason = "tool_calls"))).isEqualTo(FailureKind.INVALID_OUTPUT)
        assertThat(kindFor("""{"id":"gen-1","choices":[]}""")).isEqualTo(FailureKind.INVALID_OUTPUT)
        assertThat(kindFor("<html>gateway</html>")).isEqualTo(FailureKind.INVALID_OUTPUT)
    }

    @Test
    fun `http errors map to failure kinds without quoting the message`() = runTest {
        suspend fun failureFor(status: Int, metadata: String = "{}"): RouteResult<*> {
            api.reply(status, """{"error":{"code":$status,"message":"echo: позвонить маме","metadata":$metadata}}""")
            return runner.enrich(request)
        }
        val kinds = mapOf(
            401 to FailureKind.AUTH,
            402 to FailureKind.AUTH,
            403 to FailureKind.AUTH,
            408 to FailureKind.NETWORK,
            429 to FailureKind.RATE_LIMIT,
            502 to FailureKind.OVERLOADED,
            503 to FailureKind.OVERLOADED,
            400 to FailureKind.BAD_REQUEST,
            404 to FailureKind.BAD_REQUEST,
        )
        kinds.forEach { (status, kind) ->
            val failed = failureFor(status) as RouteResult.Failed
            assertThat(failed.kind).isEqualTo(kind)
            assertThat(failed.detail).isEqualTo("http_$status")
        }
        val moderated = failureFor(403, """{"reasons":["violence"],"flagged_input":"позвонить маме","provider_name":"x"}""")
        assertThat(moderated).isEqualTo(RouteResult.Refused(OpenRouterRouteRunner.MODERATION))
    }

    @Test
    fun `an error inside a successful answer is classified by its code`() = runTest {
        api.reply(200, """{"error":{"code":502,"message":"Provider returned error"}}""")
        val failed = runner.enrich(request) as RouteResult.Failed
        assertThat(failed.kind).isEqualTo(FailureKind.OVERLOADED)
        assertThat(failed.detail).isEqualTo("error_502")

        api.reply(200, completion(content = null, finishReason = "error", nativeFinishReason = "error"))
        val midway = runner.enrich(request) as RouteResult.Failed
        assertThat(midway.kind).isEqualTo(FailureKind.OVERLOADED)
        assertThat(midway.usage).isNotNull()
    }

    @Test
    fun `unbilled failures are retried with backoff, billed ones are not`() = runTest {
        val retrying = runner(maxRetries = 2)
        api.reply(503, """{"error":{"code":503,"message":"busy"}}""")
        api.then(200, completion(answer(estimate = "S")))
        assertThat((retrying.enrich(request) as RouteResult.Success).value.estimate).isEqualTo("S")
        assertThat(api.requests).hasSize(2)

        api.requests.clear()
        api.reply(429, """{"error":{"code":429,"message":"slow down"}}""")
        assertThat((retrying.enrich(request) as RouteResult.Failed).kind).isEqualTo(FailureKind.RATE_LIMIT)
        assertThat(api.requests).hasSize(3)

        api.requests.clear()
        api.reply(400, """{"error":{"code":400,"message":"bad"}}""")
        assertThat((retrying.enrich(request) as RouteResult.Failed).kind).isEqualTo(FailureKind.BAD_REQUEST)
        assertThat(api.requests).hasSize(1)

        api.requests.clear()
        api.reply(200, completion(content = null, finishReason = "error"))
        assertThat((retrying.enrich(request) as RouteResult.Failed).kind.retryable).isTrue()
        assertThat(api.requests).hasSize(1)
    }

    @Test
    fun `connection failures and timeouts are network errors`() = runBlocking {
        val refused = OpenRouterRouteRunner(
            HttpClient(MockEngine { throw ConnectException("refused") }) { openRouterTimeouts() },
            apiKey = "sk-or-v1-test",
            options = OpenRouterOptions(baseUrl = BASE_URL, maxRetries = 0),
        )
        val offline = refused.enrich(request) as RouteResult.Failed
        assertThat(offline.kind).isEqualTo(FailureKind.NETWORK)
        assertThat(offline.kind.retryable).isTrue()

        val slow = OpenRouterRouteRunner(
            HttpClient(
                MockEngine {
                    delay(10_000)
                    respond(completion(answer()), HttpStatusCode.OK)
                },
            ) { openRouterTimeouts(requestTimeout = 200.milliseconds) },
            apiKey = "sk-or-v1-test",
            options = OpenRouterOptions(baseUrl = BASE_URL, maxRetries = 0),
        )
        assertThat((slow.enrich(request) as RouteResult.Failed).kind).isEqualTo(FailureKind.NETWORK)
    }

    @Test
    fun `invalid request fails locally without calling the API`() = runTest {
        val result = runner.enrich(request.copy(text = "", timeZone = "nowhere")) as RouteResult.Failed
        assertThat(result.kind).isEqualTo(FailureKind.BAD_REQUEST)
        assertThat(result.detail).isEqualTo("invalid request fields: text, timeZone")
        assertThat(api.requests).isEmpty()
    }

    @Test
    fun `keys and endpoints that cannot be sent safely are rejected up front`() {
        val http = HttpClient(api.engine)
        assertThrows(IllegalArgumentException::class.java) { OpenRouterRouteRunner(http, "sk-or-v1-ключ") }
        assertThrows(IllegalArgumentException::class.java) { OpenRouterRouteRunner(http, "sk-or v1") }
        assertThrows(IllegalArgumentException::class.java) { OpenRouterOptions(baseUrl = "http://openrouter.ai/api/v1") }
    }

    private fun answer(
        estimate: String? = null,
        confidence: Double? = if (estimate != null) 0.8 else null,
        deadlineDate: String? = null,
        deadlineFragment: String? = null,
    ): String = JsonObject(
        mapOf(
            "estimate" to JsonPrimitive(estimate),
            "estimateConfidence" to JsonPrimitive(confidence),
            "deadlineDate" to JsonPrimitive(deadlineDate),
            "deadlineTime" to JsonPrimitive(null as String?),
            "deadlineFragment" to JsonPrimitive(deadlineFragment),
            "planDate" to JsonPrimitive(null as String?),
            "planDateFragment" to JsonPrimitive(null as String?),
        ),
    ).toString()

    /** An OpenRouter chat completion as it comes back for an Anthropic model. */
    private fun completion(
        content: String?,
        finishReason: String? = "stop",
        nativeFinishReason: String? = "end_turn",
        refusal: String? = null,
        usage: String = """{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150,"cost":0.0014}""",
        contentElement: JsonElement = JsonPrimitive(content),
    ): String = buildJsonObject {
        put("id", "gen-1")
        put("object", "chat.completion")
        put("model", "anthropic/claude-opus-5.5")
        put("provider", "Google")
        putJsonArray("choices") {
            addJsonObject {
                put("index", 0)
                put("finish_reason", finishReason)
                put("native_finish_reason", nativeFinishReason)
                putJsonObject("message") {
                    put("role", "assistant")
                    put("content", contentElement)
                    put("refusal", refusal)
                }
            }
        }
        put("usage", Json.parseToJsonElement(usage))
    }.toString()

    private class Recorded(val url: String, val authorization: String?, val body: String)

    /** Stand-in for OpenRouter: records requests and answers from a queue whose last reply repeats. */
    private class FakeOpenRouter {
        val requests: MutableList<Recorded> = CopyOnWriteArrayList()
        private val replies = ArrayDeque<Pair<Int, String>>()

        val engine = MockEngine { request ->
            requests += Recorded(
                url = request.url.toString(),
                authorization = request.headers[HttpHeaders.Authorization],
                body = (request.body as TextContent).text,
            )
            val (status, body) = synchronized(replies) { if (replies.size > 1) replies.removeFirst() else replies.first() }
            respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
        }

        fun reply(status: Int, body: String) = synchronized(replies) {
            replies.clear()
            replies += status to body
        }

        fun then(status: Int, body: String) = synchronized(replies) { replies += status to body }
    }

    private companion object {
        const val BASE_URL = "https://openrouter.test/api/v1"
    }
}
