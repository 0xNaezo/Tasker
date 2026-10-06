package app.tasker.core.ai

import app.tasker.core.ai.claude.createClient
import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.EstimateScale
import app.tasker.core.ai.contract.ExtractedFields
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.model.AiSettings
import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The direct mode end to end: the real SDK client against a local stand-in for the Claude API. */
@RunWith(RobolectricTestRunner::class)
class DirectAiGatewayTest {
    private val api = FakeClaudeApi()
    private val env = AiTestEnv()
    private val dir: File = Files.createTempDirectory("vault").toFile()
    private val keys = ApiKeyStore(testVault(dir))
    private val createdFor = mutableListOf<String>()
    private val gateway = DirectAiGateway(keys, env.settings, Dispatchers.IO) { apiKey: String ->
        createdFor += apiKey
        createClient(apiKey, baseUrl = api.baseUrl, maxRetries = 0)
    }

    @After
    fun tearDown() {
        api.close()
        env.close()
        dir.deleteRecursively()
    }

    @Test
    fun `sends the request with the stored key and the model from the settings`() = runTest {
        keys.save("sk-ant-user-key")
        env.settings.update { it.copy(ai = it.ai.copy(model = "claude-sonnet-5-5")) }
        api.reply(200, message(answer(estimate = "\"S\"", confidence = "0.9")))

        val result = gateway.enrich(REQUEST)

        assertThat(result).isInstanceOf(RouteResult.Success::class.java)
        assertThat((result as RouteResult.Success).value).isEqualTo(EnrichResponse(estimate = "S", estimateConfidence = 0.9))
        val sent = api.requests.single()
        assertThat(sent.headers["x-api-key"]).isEqualTo("sk-ant-user-key")
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertThat(body.getValue("model").jsonPrimitive.content).isEqualTo("claude-sonnet-5-5")
        assertThat(body.getValue("fallbacks").jsonPrimitive.content).isEqualTo("default")
        assertThat(sent.body).contains("позвонить в банк")
    }

    @Test
    fun `one client per key, replaced when the key changes`() = runTest {
        api.reply(200, message(answer()))
        keys.save("sk-ant-first")
        gateway.enrich(REQUEST)
        gateway.enrich(REQUEST)

        keys.save("sk-ant-second")
        gateway.enrich(REQUEST)

        assertThat(createdFor).containsExactly("sk-ant-first", "sk-ant-second").inOrder()
        assertThat(api.requests.map { it.headers["x-api-key"] }).containsExactly("sk-ant-first", "sk-ant-first", "sk-ant-second").inOrder()
    }

    @Test
    fun `without a key nothing is sent`() = runTest {
        val result = gateway.enrich(REQUEST) as RouteResult.Failed

        assertThat(result.kind).isEqualTo(FailureKind.AUTH)
        assertThat(api.requests).isEmpty()
        assertThat(createdFor).isEmpty()
    }

    @Test
    fun `a rejected key is no access, an overloaded API is retryable`() = runTest {
        keys.save("sk-ant-revoked")

        api.reply(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        assertThat((gateway.enrich(REQUEST) as RouteResult.Failed).kind).isEqualTo(FailureKind.AUTH)

        api.reply(529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        val overloaded = gateway.enrich(REQUEST) as RouteResult.Failed
        assertThat(overloaded.kind).isEqualTo(FailureKind.OVERLOADED)
        assertThat(overloaded.kind.retryable).isTrue()
    }

    @Test
    fun `route settings follow the chosen model`() {
        val default = DirectAiGateway.routeSettingsFor(" ")
        assertThat(default.model).isEqualTo(AiSettings.DEFAULT_MODEL)
        assertThat(default.fallbacks).isTrue()
        assertThat(default.effort).isEqualTo("low")
        assertThat(DirectAiGateway.routeSettingsFor("claude-opus-5-5").fallbacks).isTrue()
        assertThat(DirectAiGateway.routeSettingsFor("claude-sonnet-5-5").fallbacks).isTrue()
        assertThat(DirectAiGateway.routeSettingsFor("claude-haiku-4-5").fallbacks).isFalse()
        assertThat(DirectAiGateway.routeSettingsFor("claude-haiku-4-5").model).isEqualTo("claude-haiku-4-5")
    }

    /** The structured answer of `/v1/enrich`; every field is required by the schema. */
    private fun answer(estimate: String = "null", confidence: String = "null"): String =
        """{"estimate":$estimate,"estimateConfidence":$confidence,"deadlineDate":null,"deadlineTime":null,""" +
            """"deadlineFragment":null,"planDate":null,"planDateFragment":null}"""

    /** A Messages API response whose content starts with an (omitted) thinking block, as on Claude Opus 5.5. */
    private fun message(text: String): String = """
        {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
         "content":[{"type":"thinking","thinking":"","signature":"c2ln"},{"type":"text","text":${Json.encodeToString(text)}}],
         "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":100,"output_tokens":50}}
    """.trimIndent()

    private class Received(val headers: Map<String, String>, val body: String)

    /** Local stand-in for the Claude API on the loopback interface; tests never reach the real network. */
    private class FakeClaudeApi : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val requests = CopyOnWriteArrayList<Received>()

        @Volatile private var status = 200

        @Volatile private var responseBody = "{}"

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/") { exchange ->
                exchange.use {
                    val headers = it.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.joinToString(",") }
                    requests += Received(headers, it.requestBody.readBytes().decodeToString())
                    val bytes = responseBody.toByteArray()
                    it.responseHeaders.add("Content-Type", "application/json")
                    it.sendResponseHeaders(status, bytes.size.toLong())
                    it.responseBody.write(bytes)
                }
            }
            server.start()
        }

        fun reply(status: Int, body: String) {
            this.status = status
            this.responseBody = body
        }

        override fun close() = server.stop(0)
    }

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
