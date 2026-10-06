package app.tasker.core.ai.claude

import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.EnrichRoute
import app.tasker.core.ai.contract.EstimateScale
import app.tasker.core.ai.contract.ExtractedFields
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.ai.contract.RouteSettings
import app.tasker.core.ai.contract.RouteUsage
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import java.net.ServerSocket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Test

class ClaudeRouteRunnerTest {

    private val api = FakeClaudeApi()
    private val runner = ClaudeRouteRunner(createClient("test-key", baseUrl = api.baseUrl, maxRetries = 0))

    private val request = EnrichRequest(
        text = "подготовить квартальный отчёт до конца месяца",
        language = "ru",
        now = "2026-10-06T10:00",
        today = "2026-10-06",
        timeZone = "Europe/Kyiv",
        estimateScale = EstimateScale(15, 60, 180),
        extracted = ExtractedFields(),
    )

    @After
    fun tearDown() = api.close()

    @Test
    fun `built params carry model, low effort, schema, fallbacks and no thinking`() {
        val params = runner.buildParams(EnrichRoute, request, EnrichRoute.defaultSettings)
        assertThat(params.model().asString()).isEqualTo("claude-opus-5-5")
        assertThat(params.maxTokens()).isEqualTo(4_096)
        assertThat(params.outputConfig().get().effort().get()).isEqualTo(BetaOutputConfig.Effort.LOW)
        assertThat(params.betas().get()).contains(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
        assertThat(params.fallbacks().get().isDefault()).isTrue()
        assertThat(params.thinking().isPresent).isFalse()

        val body = Json.parseToJsonElement(ObjectMapper().writeValueAsString(JsonValue.from(params._body()))).jsonObject
        assertThat(body.getValue("output_config").jsonObject.getValue("format").jsonObject.getValue("schema"))
            .isEqualTo(EnrichRoute.outputSchema)
        assertThat(body.getValue("fallbacks")).isEqualTo(JsonPrimitive("default"))
        val system = body.getValue("system").jsonArray.single().jsonObject
        assertThat(system.getValue("text").jsonPrimitive.content).isEqualTo(EnrichRoute.systemPrompt)
        assertThat(system.getValue("cache_control")).isEqualTo(buildJsonObject { put("type", "ephemeral") })
    }

    @Test
    fun `fallbacks can be switched off for models without them`() {
        val params = runner.buildParams(EnrichRoute, request, RouteSettings(model = "claude-haiku-4-5", fallbacks = false))
        assertThat(params.fallbacks().isPresent).isFalse()
        assertThat(params.betas().orElse(emptyList())).doesNotContain(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
        assertThat(params.model().asString()).isEqualTo("claude-haiku-4-5")
    }

    @Test
    fun `wire request uses the beta endpoint with a stable cached prefix`() {
        api.reply(200, message(answer(estimate = "L")))
        runner.enrich(request)
        runner.enrich(request.copy(text = "позвонить маме", today = "2026-10-07", now = "2026-10-07T09:00"))

        val (first, second) = api.requests
        assertThat(first.method).isEqualTo("POST")
        assertThat(first.path).isEqualTo("/v1/messages")
        assertThat(first.query).contains("beta=true")
        assertThat(first.header("anthropic-beta")).contains("server-side-fallback-2026-07-01")
        assertThat(first.header("x-api-key")).isEqualTo("test-key")

        val body = Json.parseToJsonElement(first.body).jsonObject
        assertThat(body.getValue("model").jsonPrimitive.content).isEqualTo("claude-opus-5-5")
        assertThat(body.getValue("max_tokens").jsonPrimitive.content).isEqualTo("4096")
        assertThat(body.getValue("output_config").jsonObject.getValue("effort").jsonPrimitive.content).isEqualTo("low")
        assertThat(body.getValue("output_config").jsonObject.getValue("format").jsonObject.getValue("type").jsonPrimitive.content)
            .isEqualTo("json_schema")
        assertThat(body.getValue("fallbacks").jsonPrimitive.content).isEqualTo("default")
        assertThat(body.keys).containsNoneOf("thinking", "tool_choice", "tools", "temperature")
        val userTurn = body.getValue("messages").jsonArray.single().jsonObject
        assertThat(userTurn.getValue("role").jsonPrimitive.content).isEqualTo("user")
        assertThat(userTurn.getValue("content").toString()).contains("до конца месяца")

        // Prompt caching: everything before the user turn is byte-identical across different tasks and days.
        val secondBody = Json.parseToJsonElement(second.body).jsonObject
        assertThat(secondBody.getValue("system").toString()).isEqualTo(body.getValue("system").toString())
        assertThat(secondBody.getValue("output_config").toString()).isEqualTo(body.getValue("output_config").toString())
        assertThat(secondBody.getValue("messages").toString()).isNotEqualTo(body.getValue("messages").toString())
    }

    @Test
    fun `answer after thinking blocks is parsed, validated and priced`() {
        api.reply(
            200,
            message(
                answer(estimate = "L", confidence = 0.72, deadlineDate = "2026-10-31", deadlineFragment = "До конца месяца"),
                usage = """{"input_tokens":180,"output_tokens":95,"cache_read_input_tokens":1450,"cache_creation_input_tokens":0}""",
            ),
        )
        val result = runner.enrich(request)

        assertThat(result).isInstanceOf(RouteResult.Success::class.java)
        val success = result as RouteResult.Success<EnrichResponse>
        assertThat(success.value).isEqualTo(
            EnrichResponse(
                estimate = "L",
                estimateConfidence = 0.72,
                deadlineDate = "2026-10-31",
                deadlineFragment = "до конца месяца",
            ),
        )
        assertThat(success.usage).isEqualTo(RouteUsage("claude-opus-5-5", 180, 95, cacheReadTokens = 1450))
    }

    @Test
    fun `semantic validation drops dates whose fragment is not in the text`() {
        api.reply(200, message(answer(estimate = "M", deadlineDate = "2026-10-31", deadlineFragment = "end of month")))
        val value = (runner.enrich(request) as RouteResult.Success).value
        assertThat(value.estimate).isEqualTo("M")
        assertThat(value.deadlineDate).isNull()
    }

    @Test
    fun `refusal is reported with its category before content is read`() {
        api.reply(
            200,
            """
            {"id":"msg_r","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],
             "stop_reason":"refusal","stop_sequence":null,
             "stop_details":{"type":"refusal","category":"cyber","explanation":null},
             "usage":{"input_tokens":1500,"output_tokens":0}}
            """.trimIndent(),
        )
        val result = runner.enrich(request)
        assertThat(result).isEqualTo(RouteResult.Refused("cyber", RouteUsage("claude-opus-5-5", 1500, 0)))
    }

    @Test
    fun `max_tokens stop is a truncation, not an answer`() {
        api.reply(200, message("""{"estimate":"L","estim""", stopReason = "max_tokens"))
        val result = runner.enrich(request)
        assertThat((result as RouteResult.Failed).kind).isEqualTo(FailureKind.TRUNCATED)
        assertThat(result.usage).isNotNull()
    }

    @Test
    fun `malformed or missing text is invalid output`() {
        api.reply(200, message("not json"))
        assertThat((runner.enrich(request) as RouteResult.Failed).kind).isEqualTo(FailureKind.INVALID_OUTPUT)

        api.reply(200, message(text = null))
        assertThat((runner.enrich(request) as RouteResult.Failed).kind).isEqualTo(FailureKind.INVALID_OUTPUT)

        api.reply(200, message(answer(estimate = "M"), stopReason = "tool_use"))
        assertThat((runner.enrich(request) as RouteResult.Failed).kind).isEqualTo(FailureKind.INVALID_OUTPUT)
    }

    @Test
    fun `server-side fallback usage keeps the declined attempt and the serving model`() {
        api.reply(
            200,
            """
            {"id":"msg_f","type":"message","role":"assistant","model":"claude-opus-5",
             "content":[
               {"type":"fallback","from":{"model":"claude-opus-5-5"},"to":{"model":"claude-opus-5"}},
               {"type":"text","text":${JsonPrimitive(answer(estimate = "S"))}}
             ],
             "stop_reason":"end_turn","stop_sequence":null,
             "usage":{"input_tokens":1600,"output_tokens":120,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,
               "iterations":[
                 {"type":"message","model":"claude-opus-5-5","input_tokens":1600,"output_tokens":0,
                  "cache_read_input_tokens":0,"cache_creation_input_tokens":0},
                 {"type":"fallback_message","model":"claude-opus-5","input_tokens":1600,"output_tokens":120,
                  "cache_read_input_tokens":0,"cache_creation_input_tokens":0}
               ]}}
            """.trimIndent(),
        )
        val success = runner.enrich(request) as RouteResult.Success
        assertThat(success.value.estimate).isEqualTo("S")
        assertThat(success.usage).isEqualTo(
            RouteUsage(
                model = "claude-opus-5",
                inputTokens = 1600,
                outputTokens = 120,
                declinedAttempts = listOf(RouteUsage("claude-opus-5-5", 1600, 0)),
            ),
        )
    }

    @Test
    fun `http errors map to failure kinds`() {
        fun failureFor(status: Int, type: String): FailureKind {
            api.reply(status, """{"type":"error","error":{"type":"$type","message":"test"}}""")
            return (runner.enrich(request) as RouteResult.Failed).kind
        }
        assertThat(failureFor(401, "authentication_error")).isEqualTo(FailureKind.AUTH)
        assertThat(failureFor(403, "permission_error")).isEqualTo(FailureKind.AUTH)
        assertThat(failureFor(402, "billing_error")).isEqualTo(FailureKind.AUTH)
        assertThat(failureFor(429, "rate_limit_error")).isEqualTo(FailureKind.RATE_LIMIT)
        assertThat(failureFor(529, "overloaded_error")).isEqualTo(FailureKind.OVERLOADED)
        assertThat(failureFor(500, "api_error")).isEqualTo(FailureKind.OVERLOADED)
        assertThat(failureFor(400, "invalid_request_error")).isEqualTo(FailureKind.BAD_REQUEST)
        assertThat(failureFor(404, "not_found_error")).isEqualTo(FailureKind.BAD_REQUEST)
    }

    @Test
    fun `connection failure is a network error`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val offline = ClaudeRouteRunner(createClient("test-key", baseUrl = "http://127.0.0.1:$closedPort", maxRetries = 0))
        val result = offline.enrich(request) as RouteResult.Failed
        assertThat(result.kind).isEqualTo(FailureKind.NETWORK)
        assertThat(result.kind.retryable).isTrue()
    }

    @Test
    fun `invalid request fails locally without calling the API`() {
        val result = runner.enrich(request.copy(text = "", timeZone = "nowhere")) as RouteResult.Failed
        assertThat(result.kind).isEqualTo(FailureKind.BAD_REQUEST)
        assertThat(result.detail).isEqualTo("invalid request fields: text, timeZone")
        assertThat(api.requests).isEmpty()
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

    /** A Messages API response whose content starts with an (omitted) thinking block, as on Claude Opus 5.5. */
    private fun message(
        text: String?,
        stopReason: String = "end_turn",
        usage: String = """{"input_tokens":100,"output_tokens":50}""",
    ): String {
        val textBlock = text?.let { """,{"type":"text","text":${JsonPrimitive(it)}}""" }.orEmpty()
        return """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"thinking","thinking":"","signature":"c2ln"}$textBlock],
             "stop_reason":"$stopReason","stop_sequence":null,"usage":$usage}
        """.trimIndent()
    }
}
