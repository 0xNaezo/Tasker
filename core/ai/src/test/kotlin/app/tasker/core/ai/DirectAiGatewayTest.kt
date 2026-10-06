package app.tasker.core.ai

import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.EstimateScale
import app.tasker.core.ai.contract.ExtractedFields
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.ai.openrouter.openRouterTimeouts
import app.tasker.core.model.AiSettings
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The direct mode end to end: the real runner and HTTP client against a stand-in for OpenRouter. */
@RunWith(RobolectricTestRunner::class)
class DirectAiGatewayTest {
    private val env = AiTestEnv()
    private val dir: File = Files.createTempDirectory("vault").toFile()
    private val keys = ApiKeyStore(testVault(dir))
    private val requests = CopyOnWriteArrayList<Sent>()

    @Volatile
    private var status = HttpStatusCode.OK

    @Volatile
    private var answer = completion(answer())

    private val engine = MockEngine { request ->
        requests += Sent(request.url.toString(), request.headers[HttpHeaders.Authorization], (request.body as TextContent).text)
        respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    private val gateway = DirectAiGateway(keys, env.settings, HttpClient(engine) { openRouterTimeouts() })

    @After
    fun tearDown() {
        env.close()
        dir.deleteRecursively()
    }

    @Test
    fun `sends the request with the stored key and the model from the settings`() = runTest {
        keys.save("sk-or-v1-user-key")
        env.settings.update { it.copy(ai = it.ai.copy(model = "anthropic/claude-sonnet-5.5")) }
        answer = completion(answer(estimate = "\"S\"", confidence = "0.9"))

        val result = gateway.enrich(REQUEST)

        assertThat(result).isInstanceOf(RouteResult.Success::class.java)
        assertThat((result as RouteResult.Success).value).isEqualTo(EnrichResponse(estimate = "S", estimateConfidence = 0.9))
        val sent = requests.single()
        assertThat(sent.url).isEqualTo("https://openrouter.ai/api/v1/chat/completions")
        assertThat(sent.authorization).isEqualTo("Bearer sk-or-v1-user-key")
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertThat(body.getValue("model").jsonPrimitive.content).isEqualTo("anthropic/claude-sonnet-5.5")
        assertThat(body.getValue("provider").jsonObject.getValue("zdr").jsonPrimitive.content).isEqualTo("true")
        assertThat(sent.body).contains("позвонить в банк")
    }

    @Test
    fun `a new key is used from the next call on`() = runTest {
        keys.save("sk-or-v1-first")
        gateway.enrich(REQUEST)
        keys.save("sk-or-v1-second")
        gateway.enrich(REQUEST)

        assertThat(requests.map { it.authorization }).containsExactly("Bearer sk-or-v1-first", "Bearer sk-or-v1-second").inOrder()
    }

    @Test
    fun `without a key nothing is sent`() = runTest {
        val result = gateway.enrich(REQUEST) as RouteResult.Failed

        assertThat(result.kind).isEqualTo(FailureKind.AUTH)
        assertThat(result.detail).isEqualTo(AiUnavailableReason.NO_API_KEY.code)
        assertThat(requests).isEmpty()
    }

    @Test
    fun `a rejected key is no access, a failing provider is retryable`() = runTest {
        keys.save("sk-or-v1-revoked")

        status = HttpStatusCode.Unauthorized
        answer = """{"error":{"code":401,"message":"User not found."}}"""
        assertThat((gateway.enrich(REQUEST) as RouteResult.Failed).kind).isEqualTo(FailureKind.AUTH)

        status = HttpStatusCode.PaymentRequired
        answer = """{"error":{"code":402,"message":"Insufficient credits"}}"""
        assertThat((gateway.enrich(REQUEST) as RouteResult.Failed).kind).isEqualTo(FailureKind.AUTH)

        status = HttpStatusCode.OK
        answer = """{"error":{"code":502,"message":"Provider returned error"}}"""
        val failing = gateway.enrich(REQUEST) as RouteResult.Failed
        assertThat(failing.kind).isEqualTo(FailureKind.OVERLOADED)
        assertThat(failing.kind.retryable).isTrue()
    }

    @Test
    fun `route settings follow the chosen model`() {
        val default = DirectAiGateway.routeSettingsFor(" ")
        assertThat(default.model).isEqualTo(AiSettings.DEFAULT_MODEL)
        assertThat(default.effort).isEqualTo("low")
        assertThat(default.zeroDataRetention).isTrue()
        assertThat(DirectAiGateway.routeSettingsFor("anthropic/claude-haiku-4.5").model).isEqualTo("anthropic/claude-haiku-4.5")
        // An id saved before the switch to OpenRouter names no provider: the default model is used instead.
        assertThat(DirectAiGateway.routeSettingsFor("claude-opus-5-5").model).isEqualTo(AiSettings.DEFAULT_MODEL)
    }

    /** The structured answer of `/v1/enrich`; every field is required by the schema. */
    private fun answer(estimate: String = "null", confidence: String = "null"): String =
        """{"estimate":$estimate,"estimateConfidence":$confidence,"deadlineDate":null,"deadlineTime":null,""" +
            """"deadlineFragment":null,"planDate":null,"planDateFragment":null}"""

    /** An OpenRouter chat completion with the answer as the message content. */
    private fun completion(content: String): String = """
        {"id":"gen-1","object":"chat.completion","model":"anthropic/claude-opus-5.5",
         "choices":[{"index":0,"finish_reason":"stop","native_finish_reason":"end_turn",
           "message":{"role":"assistant","content":${Json.encodeToString(content)}}}],
         "usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150,"cost":0.0014}}
    """.trimIndent()

    private class Sent(val url: String, val authorization: String?, val body: String)

    private companion object {
        val REQUEST = EnrichRequest(
            text = "позвонить в банк насчёт карты",
            language = "ru",
            now = "2026-10-06T10:00",
            today = "2026-10-06",
            timeZone = "Europe/Kyiv",
            estimateScale = EstimateScale(15, 60, 180),
            extracted = ExtractedFields(),
        )
    }
}
