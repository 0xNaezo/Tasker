package app.tasker.backend

import app.tasker.backend.auth.IntegrityVerdict
import app.tasker.backend.auth.PlayIntegrityPolicy
import app.tasker.backend.auth.PlayIntegrityVerifier
import app.tasker.core.ai.contract.IntegrityBinding
import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Test

class PlayIntegrityTest {

    private val packageName = "app.tasker"
    private val maxAge = Duration.ofMinutes(10)
    private val issuedAt = TEST_NOW.minusSeconds(30)

    private fun payload(
        requestPackage: String = packageName,
        binding: String = "\"requestHash\": \"${IntegrityBinding.requestHash(INSTALL_ID)}\"",
        timestamp: Instant = issuedAt,
        recognition: String = "PLAY_RECOGNIZED",
        appPackage: String = packageName,
        device: String = "\"MEETS_DEVICE_INTEGRITY\"",
    ): JsonObject = Json.parseToJsonElement(
        """
        {"tokenPayloadExternal": {
          "requestDetails": {"requestPackageName": "$requestPackage", $binding, "timestampMillis": "${timestamp.toEpochMilli()}"},
          "appIntegrity": {"appRecognitionVerdict": "$recognition", "packageName": "$appPackage", "versionCode": "42"},
          "deviceIntegrity": {"deviceRecognitionVerdict": [$device]},
          "accountDetails": {"appLicensingVerdict": "LICENSED"}
        }}
        """.trimIndent(),
    ).jsonObject

    private fun evaluate(payload: JsonObject) = PlayIntegrityPolicy.evaluate(payload, packageName, INSTALL_ID, TEST_NOW, maxAge)

    @Test
    fun `genuine app on a genuine device passes`() {
        assertThat(evaluate(payload())).isEqualTo(IntegrityVerdict.Valid)
        assertThat(evaluate(payload(device = "\"MEETS_BASIC_INTEGRITY\", \"MEETS_STRONG_INTEGRITY\""))).isEqualTo(IntegrityVerdict.Valid)
        val classicNonce = "\"nonce\": \"${IntegrityBinding.requestHash(INSTALL_ID)}=\""
        assertThat(evaluate(payload(binding = classicNonce))).isEqualTo(IntegrityVerdict.Valid)
    }

    @Test
    fun `each policy violation has its own reason`() {
        val cases = mapOf(
            payload(requestPackage = "com.example.clone") to "request_package",
            payload(binding = "\"requestHash\": \"${IntegrityBinding.requestHash("another-install")}\"") to "request_binding",
            payload(timestamp = TEST_NOW.minus(Duration.ofMinutes(11))) to "stale_token",
            payload(timestamp = TEST_NOW.plus(Duration.ofMinutes(5))) to "stale_token",
            payload(recognition = "UNRECOGNIZED_VERSION") to "app_not_recognized",
            payload(appPackage = "com.example.clone") to "app_package",
            payload(device = "\"MEETS_BASIC_INTEGRITY\"") to "device_integrity",
            payload(device = "") to "device_integrity",
        )
        for ((payload, reason) in cases) {
            assertThat(evaluate(payload)).isEqualTo(IntegrityVerdict.Rejected(reason))
        }
        assertThat(evaluate(JsonObject(emptyMap()))).isEqualTo(IntegrityVerdict.Rejected("request_details_missing"))
    }

    private var server: HttpServer? = null
    private val received = mutableListOf<Triple<String, String?, String>>()

    private fun startGoogle(status: Int, body: String): String {
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        started.createContext("/") { exchange ->
            val requestBody = exchange.requestBody.readBytes().decodeToString()
            synchronized(received) {
                received += Triple(exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"), requestBody)
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        started.start()
        server = started
        return "http://127.0.0.1:${started.address.port}"
    }

    @After
    fun stopServer() {
        server?.stop(0)
        server = null
    }

    private fun verifier(endpoint: String) =
        PlayIntegrityVerifier(packageName, accessToken = { "fake-access-token" }, clock = MutableClock(TEST_NOW), endpoint = endpoint)

    @Test
    fun `verifier decodes the token with google and applies the policy`() = runBlocking {
        val endpoint = startGoogle(200, payload().toString())

        assertThat(verifier(endpoint).verify(INSTALL_ID, "integrity-token-123")).isEqualTo(IntegrityVerdict.Valid)
        val (path, authorization, body) = received.single()
        assertThat(path).isEqualTo("/v1/app.tasker:decodeIntegrityToken")
        assertThat(authorization).isEqualTo("Bearer fake-access-token")
        assertThat(body).isEqualTo("{\"integrityToken\":\"integrity-token-123\"}")

        assertThat(verifier(endpoint).verify("another-install-1", "integrity-token-123"))
            .isEqualTo(IntegrityVerdict.Rejected("request_binding"))
    }

    @Test
    fun `google errors map to rejected or unavailable`() = runBlocking {
        assertThat(verifier(startGoogle(400, "{}")).verify(INSTALL_ID, "t")).isEqualTo(IntegrityVerdict.Rejected("token_invalid"))
        stopServer()
        assertThat(verifier(startGoogle(403, "{}")).verify(INSTALL_ID, "t")).isEqualTo(IntegrityVerdict.Unavailable("google_credentials"))
        stopServer()
        assertThat(verifier(startGoogle(503, "")).verify(INSTALL_ID, "t")).isEqualTo(IntegrityVerdict.Unavailable("google_http_503"))
        stopServer()
        assertThat(verifier(startGoogle(200, "<html>")).verify(INSTALL_ID, "t")).isEqualTo(IntegrityVerdict.Unavailable("google_response"))
        stopServer()

        val closed = startGoogle(200, "{}")
        stopServer()
        assertThat(verifier(closed).verify(INSTALL_ID, "t")).isEqualTo(IntegrityVerdict.Unavailable("network"))
    }
}
