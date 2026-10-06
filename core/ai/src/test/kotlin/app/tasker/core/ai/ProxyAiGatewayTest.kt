package app.tasker.core.ai

import app.tasker.core.ai.contract.AiJson
import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.EstimateScale
import app.tasker.core.ai.contract.ExtractedFields
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.InstallIds
import app.tasker.core.ai.contract.InstallRequest
import app.tasker.core.ai.contract.IntegrityBinding
import app.tasker.core.ai.contract.MetricsReport
import app.tasker.core.ai.contract.ProxyProtocol
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.data.metrics.WeeklyMetrics
import app.tasker.core.domain.time.DayClock
import app.tasker.core.testing.TestTimeSource
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProxyAiGatewayTest {
    private val time = TestTimeSource.at()
    private val clock = DayClock(time)
    private val dir: File = Files.createTempDirectory("vault").toFile()
    private val aead = testAead()
    private val integrityRequests = mutableListOf<String>()
    private var integrityResult: IntegrityTokenResult = IntegrityTokenResult.Token("play-integrity-token")
    private val integrity = IntegrityTokenProvider { hash ->
        integrityRequests += hash
        integrityResult
    }

    private val requests = mutableListOf<Recorded>()
    private var issued = 0

    /** Answers `/v1/enrich`; by default every token issued by the fake install route is valid. */
    private var enrich: MockRequestHandleScope.(token: String?) -> HttpResponseData = { token ->
        if (token != null && token.startsWith("install-token-")) json(ANSWER) else errorReply(401, "unauthorized")
    }

    /** Answers `/v1/metrics` like the backend: 204 for a valid install token. */
    private var metrics: MockRequestHandleScope.(token: String?) -> HttpResponseData = { token ->
        if (token != null && token.startsWith("install-token-")) respond("", HttpStatusCode.NoContent) else errorReply(401, "unauthorized")
    }
    private var install: MockRequestHandleScope.() -> HttpResponseData = {
        issued++
        json("""{"token":"install-token-$issued","expiresAtEpochSeconds":${time.now().plus(Duration.ofDays(30)).epochSecond}}""")
    }

    private val engine = MockEngine { request ->
        requests += Recorded(request, request.body.toByteArray().decodeToString())
        when (request.url.encodedPath) {
            ProxyProtocol.INSTALL_PATH -> install()
            "/v1/enrich" -> enrich(request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer "))
            ProxyProtocol.METRICS_PATH -> metrics(request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer "))
            else -> errorReply(404, "not_found")
        }
    }

    private fun gateway(environment: AiEnvironment = AiTestEnv.PROXY_ONLY) =
        ProxyAiGateway(environment, HttpClient(engine) { expectSuccess = false }, testVault(dir, aead), integrity, clock)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `installs with a Play Integrity token bound to the install id, then enriches with the install token`() = runTest {
        val result = gateway().enrich(REQUEST)

        assertThat(result).isInstanceOf(RouteResult.Success::class.java)
        assertThat((result as RouteResult.Success).value).isEqualTo(EnrichResponse(estimate = "S", estimateConfidence = 0.9))

        val (installCall, enrichCall) = requests
        assertThat(installCall.data.url.toString()).isEqualTo("https://proxy.test/v1/install")
        assertThat(installCall.data.headers[HttpHeaders.Authorization]).isNull()
        val install = AiJson.wire.decodeFromString(InstallRequest.serializer(), installCall.body)
        assertThat(InstallIds.isValid(install.installId)).isTrue()
        assertThat(install.integrityToken).isEqualTo("play-integrity-token")
        assertThat(integrityRequests).containsExactly(IntegrityBinding.requestHash(install.installId))

        assertThat(enrichCall.data.url.toString()).isEqualTo("https://proxy.test/v1/enrich")
        assertThat(enrichCall.data.headers[HttpHeaders.Authorization]).isEqualTo("Bearer install-token-1")
        assertThat(enrichCall.data.body.contentType.toString()).startsWith("application/json")
        assertThat(AiJson.wire.decodeFromString(EnrichRequest.serializer(), enrichCall.body)).isEqualTo(REQUEST)
    }

    @Test
    fun `the install token is cached, also across restarts`() = runTest {
        gateway().enrich(REQUEST)
        gateway().enrich(REQUEST)
        val restarted = gateway()
        restarted.enrich(REQUEST)

        assertThat(issued).isEqualTo(1)
        assertThat(integrityRequests).hasSize(1)
        assertThat(requests.count { it.path == "/v1/enrich" }).isEqualTo(3)
    }

    @Test
    fun `a secret store that fails costs one install per process, not one per call`() = runTest {
        gateway().enrich(REQUEST)
        val broken = SecretVault(dir, { throw GeneralSecurityException("Keystore unavailable") }, Dispatchers.Unconfined)
        val gateway = ProxyAiGateway(AiTestEnv.PROXY_ONLY, HttpClient(engine) { expectSuccess = false }, broken, integrity, clock)

        assertThat(gateway.enrich(REQUEST)).isInstanceOf(RouteResult.Success::class.java)
        assertThat(gateway.enrich(REQUEST)).isInstanceOf(RouteResult.Success::class.java)

        assertThat(issued).isEqualTo(2)
        assertThat(requests.last().data.headers[HttpHeaders.Authorization]).isEqualTo("Bearer install-token-2")
        // The stored token is not touched and serves the next process once the store works again.
        gateway().enrich(REQUEST)
        assertThat(issued).isEqualTo(2)
        assertThat(requests.last().data.headers[HttpHeaders.Authorization]).isEqualTo("Bearer install-token-1")
    }

    @Test
    fun `an expired token is renewed before the call and the install id stays`() = runTest {
        val gateway = gateway()
        gateway.enrich(REQUEST)
        time.advance(Duration.ofDays(30))

        gateway.enrich(REQUEST)

        assertThat(issued).isEqualTo(2)
        val installs = requests.filter { it.path == ProxyProtocol.INSTALL_PATH }
            .map { AiJson.wire.decodeFromString(InstallRequest.serializer(), it.body).installId }
        assertThat(installs.distinct()).hasSize(1)
        assertThat(requests.last().data.headers[HttpHeaders.Authorization]).isEqualTo("Bearer install-token-2")
    }

    @Test
    fun `a 401 installs again once and repeats the request`() = runTest {
        enrich = { token -> if (token == "install-token-2") json(ANSWER) else errorReply(401, "unauthorized") }

        val result = gateway().enrich(REQUEST)

        assertThat(result).isInstanceOf(RouteResult.Success::class.java)
        assertThat(issued).isEqualTo(2)
        assertThat(requests.map { it.path }).containsExactly(
            ProxyProtocol.INSTALL_PATH,
            "/v1/enrich",
            ProxyProtocol.INSTALL_PATH,
            "/v1/enrich",
        ).inOrder()
    }

    @Test
    fun `a second 401 is no access and the queue is not hammered`() = runTest {
        enrich = { errorReply(401, "unauthorized") }
        val gateway = gateway()

        val result = gateway.enrich(REQUEST)

        assertThat((result as RouteResult.Failed).kind).isEqualTo(FailureKind.AUTH)
        assertThat(issued).isEqualTo(2)
        // The token is forgotten and installing again waits for the cool-down.
        val again = gateway.enrich(REQUEST)
        assertThat((again as RouteResult.Failed).kind).isEqualTo(FailureKind.AUTH)
        assertThat(issued).isEqualTo(2)
        // After the cool-down it tries again: install, 401, install once more.
        time.advance(ProxyAiGateway.INSTALL_COOLDOWN.plusMinutes(1))
        gateway.enrich(REQUEST)
        assertThat(issued).isEqualTo(4)
    }

    @Test
    fun `error answers map to route results as the protocol describes`() {
        val cases = mapOf(
            (400 to "invalid_request") to FailureKind.BAD_REQUEST,
            (403 to "install_blocked") to FailureKind.AUTH,
            (404 to "not_found") to FailureKind.BAD_REQUEST,
            (413 to "payload_too_large") to FailureKind.BAD_REQUEST,
            (429 to "daily_limit") to FailureKind.RATE_LIMIT,
            (500 to "internal") to FailureKind.OVERLOADED,
            (502 to "invalid_output") to FailureKind.INVALID_OUTPUT,
            (502 to "upstream_error") to FailureKind.OVERLOADED,
            (503 to "upstream_busy") to FailureKind.OVERLOADED,
            (503 to "budget_exhausted") to FailureKind.OVERLOADED,
            (503 to null) to FailureKind.OVERLOADED,
        )
        for ((answer, kind) in cases) {
            val (status, code) = answer
            val result = ProxyAiGateway.failureOf(status, code)
            assertThat(result).isInstanceOf(RouteResult.Failed::class.java)
            assertThat((result as RouteResult.Failed).kind).isEqualTo(kind)
            assertThat(result.detail).startsWith("proxy_http_$status")
        }
        assertThat(ProxyAiGateway.failureOf(422, "refused")).isEqualTo(RouteResult.Refused(category = null))
        assertThat(FailureKind.RATE_LIMIT.retryable).isTrue()
        assertThat(FailureKind.OVERLOADED.retryable).isTrue()
        assertThat(FailureKind.INVALID_OUTPUT.retryable).isFalse()
    }

    @Test
    fun `a refusal from the backend is a refusal`() = runTest {
        enrich = { errorReply(422, "refused") }

        assertThat(gateway().enrich(REQUEST)).isEqualTo(RouteResult.Refused(category = null))
    }

    @Test
    fun `Retry-After pauses further calls until then`() = runTest {
        enrich = { errorReply(429, "daily_limit", retryAfterSeconds = 120) }
        val gateway = gateway()

        val first = gateway.enrich(REQUEST) as RouteResult.Failed
        val second = gateway.enrich(REQUEST) as RouteResult.Failed

        assertThat(first.kind).isEqualTo(FailureKind.RATE_LIMIT)
        assertThat(second.kind).isEqualTo(FailureKind.RATE_LIMIT)
        assertThat(second.detail).isEqualTo("proxy_paused")
        assertThat(requests.count { it.path == "/v1/enrich" }).isEqualTo(1)

        time.advance(Duration.ofSeconds(121))
        enrich = { json(ANSWER) }
        assertThat(gateway.enrich(REQUEST)).isInstanceOf(RouteResult.Success::class.java)
    }

    @Test
    fun `a device that cannot attest gets no access and Play Integrity is not asked again for a while`() = runTest {
        integrityResult = IntegrityTokenResult.Failed(FailureKind.AUTH, "integrity_-1")
        val gateway = gateway()

        val result = gateway.enrich(REQUEST) as RouteResult.Failed
        gateway.enrich(REQUEST)

        assertThat(result.kind).isEqualTo(FailureKind.AUTH)
        assertThat(integrityRequests).hasSize(1)
        assertThat(requests).isEmpty()
    }

    @Test
    fun `a transient Play Integrity error is retryable and not cooled down`() = runTest {
        integrityResult = IntegrityTokenResult.Failed(FailureKind.NETWORK, "integrity_-3")
        val gateway = gateway()

        val result = gateway.enrich(REQUEST) as RouteResult.Failed
        gateway.enrich(REQUEST)

        assertThat(result.kind).isEqualTo(FailureKind.NETWORK)
        assertThat(integrityRequests).hasSize(2)
    }

    @Test
    fun `an install rejected by the backend is no access, not a problem of the task`() = runTest {
        install = { errorReply(403, "integrity_failed") }

        val result = gateway().enrich(REQUEST) as RouteResult.Failed

        assertThat(result.kind).isEqualTo(FailureKind.AUTH)
        assertThat(requests.map { it.path }).containsExactly(ProxyProtocol.INSTALL_PATH)
    }

    @Test
    fun `verification that is temporarily unavailable is retryable`() = runTest {
        install = { errorReply(503, "unavailable") }

        val result = gateway().enrich(REQUEST) as RouteResult.Failed

        assertThat(result.kind).isEqualTo(FailureKind.OVERLOADED)
    }

    @Test
    fun `network errors are retryable`() = runTest {
        val failing = MockEngine { throw IOException("connection reset") }
        val gateway = ProxyAiGateway(AiTestEnv.PROXY_ONLY, HttpClient(failing), testVault(dir, aead), integrity, clock)

        val result = gateway.enrich(REQUEST) as RouteResult.Failed

        assertThat(result.kind).isEqualTo(FailureKind.NETWORK)
        assertThat(result.kind.retryable).isTrue()
    }

    @Test
    fun `a malformed answer is invalid output`() = runTest {
        enrich = { respond("<html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }

        val result = gateway().enrich(REQUEST) as RouteResult.Failed

        assertThat(result.kind).isEqualTo(FailureKind.INVALID_OUTPUT)
    }

    @Test
    fun `the answer is checked again on the device`() = runTest {
        enrich = { json("""{"estimate":"m","deadlineDate":"2026-10-31","deadlineFragment":"end of month","planDate":"2020-01-01"}""") }

        val result = gateway().enrich(REQUEST) as RouteResult.Success

        assertThat(result.value).isEqualTo(EnrichResponse(estimate = "M"))
    }

    @Test
    fun `the dev key replaces Play Integrity in the dev environment`() = runTest {
        val dev = AiTestEnv.PROXY_ONLY.copy(proxyDevInstallKey = "dev-key")

        gateway(dev).enrich(REQUEST)

        val installCall = requests.first()
        assertThat(installCall.data.headers[ProxyProtocol.DEV_INSTALL_KEY_HEADER]).isEqualTo("dev-key")
        assertThat(AiJson.wire.decodeFromString(InstallRequest.serializer(), installCall.body).integrityToken).isEmpty()
        assertThat(integrityRequests).isEmpty()
        assertThat(dev.toString()).doesNotContain("dev-key")
    }

    @Test
    fun `an invalid request fails locally without a network call`() = runTest {
        val result = gateway().enrich(REQUEST.copy(text = " ")) as RouteResult.Failed

        assertThat(result.kind).isEqualTo(FailureKind.BAD_REQUEST)
        assertThat(requests).isEmpty()
        assertThat(integrityRequests).isEmpty()
    }

    @Test
    fun `the classic API nonce is the padded request hash`() {
        val hash = IntegrityBinding.requestHash("install-1234")
        val nonce = PlayIntegrityTokenProvider.nonceOf(hash)

        assertThat(hash).hasLength(43)
        assertThat(nonce).isEqualTo("$hash=")
        assertThat(java.util.Base64.getUrlDecoder().decode(nonce)).hasLength(32)
        assertThat(PlayIntegrityTokenProvider.kindOf(-3)).isEqualTo(FailureKind.NETWORK)
        assertThat(PlayIntegrityTokenProvider.kindOf(-8)).isEqualTo(FailureKind.RATE_LIMIT)
        assertThat(PlayIntegrityTokenProvider.kindOf(-12)).isEqualTo(FailureKind.OVERLOADED)
        assertThat(PlayIntegrityTokenProvider.kindOf(-1)).isEqualTo(FailureKind.AUTH)
        assertThat(IntegrityTokenResult.Token("secret").toString()).doesNotContain("secret")
    }

    @Test
    fun `weekly metrics go with the install token and name its install`() = runTest {
        val delivery = gateway().sendMetrics(WEEK)

        assertThat(delivery).isEqualTo(MetricsDelivery.SENT)
        val (installCall, metricsCall) = requests
        val installId = AiJson.wire.decodeFromString(InstallRequest.serializer(), installCall.body).installId
        assertThat(metricsCall.data.url.toString()).isEqualTo("https://proxy.test/v1/metrics")
        assertThat(metricsCall.data.headers[HttpHeaders.Authorization]).isEqualTo("Bearer install-token-1")
        val report = AiJson.wire.decodeFromString(MetricsReport.serializer(), metricsCall.body)
        assertThat(report).isEqualTo(MetricsReport(installId, "2026-09-28", WEEK.counters, WEEK.values))
        assertThat(report.problems()).isEmpty()
    }

    @Test
    fun `metrics share the install token with enrichment`() = runTest {
        val gateway = gateway()
        gateway.enrich(REQUEST)
        gateway.sendMetrics(WEEK)

        assertThat(issued).isEqualTo(1)
        assertThat(requests.last().data.headers[HttpHeaders.Authorization]).isEqualTo("Bearer install-token-1")
    }

    @Test
    fun `a 401 on metrics installs again once and sends the report again`() = runTest {
        metrics = { token -> if (token == "install-token-2") respond("", HttpStatusCode.NoContent) else errorReply(401, "unauthorized") }

        assertThat(gateway().sendMetrics(WEEK)).isEqualTo(MetricsDelivery.SENT)
        assertThat(requests.map { it.path }).containsExactly(
            ProxyProtocol.INSTALL_PATH,
            ProxyProtocol.METRICS_PATH,
            ProxyProtocol.INSTALL_PATH,
            ProxyProtocol.METRICS_PATH,
        ).inOrder()
    }

    @Test
    fun `temporary failures are retried later, refusals are not`() = runTest {
        val cases = mapOf(
            (429 to "daily_limit") to MetricsDelivery.RETRY,
            (500 to "internal") to MetricsDelivery.RETRY,
            (503 to "upstream_busy") to MetricsDelivery.RETRY,
            (400 to "invalid_request") to MetricsDelivery.REJECTED,
            (403 to "install_blocked") to MetricsDelivery.REJECTED,
            (413 to "payload_too_large") to MetricsDelivery.REJECTED,
        )
        val gateway = gateway()
        for ((answer, delivery) in cases) {
            metrics = { errorReply(answer.first, answer.second) }
            assertThat(gateway.sendMetrics(WEEK)).isEqualTo(delivery)
        }
        val offline = ProxyAiGateway(
            AiTestEnv.PROXY_ONLY,
            HttpClient(MockEngine { throw IOException("connection reset") }) { expectSuccess = false },
            testVault(dir, aead),
            integrity,
            clock,
        )
        assertThat(offline.sendMetrics(WEEK)).isEqualTo(MetricsDelivery.RETRY)
    }

    @Test
    fun `a report the backend would refuse is not sent`() = runTest {
        val broken = WEEK.copy(counters = mapOf("Tasks with text" to 1L))

        assertThat(gateway().sendMetrics(broken)).isEqualTo(MetricsDelivery.REJECTED)
        assertThat(requests.none { it.path == ProxyProtocol.METRICS_PATH }).isTrue()
    }

    @Test
    fun `without the proxy nothing is sent`() = runTest {
        assertThat(gateway(AiTestEnv.DIRECT_ONLY).sendMetrics(WEEK)).isEqualTo(MetricsDelivery.OFF)
        assertThat(requests).isEmpty()
    }

    private class Recorded(val data: HttpRequestData, val body: String) {
        val path: String get() = data.url.encodedPath
    }

    private fun MockRequestHandleScope.json(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.errorReply(status: Int, code: String, retryAfterSeconds: Long? = null): HttpResponseData {
        val headers = buildList {
            add(HttpHeaders.ContentType to listOf("application/json"))
            retryAfterSeconds?.let { add(HttpHeaders.RetryAfter to listOf(it.toString())) }
        }
        return respond(
            """{"code":"$code","message":"fixed message"}""",
            HttpStatusCode.fromValue(status),
            headersOf(*headers.toTypedArray()),
        )
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
        const val ANSWER = """{"estimate":"S","estimateConfidence":0.9}"""
        val WEEK = WeeklyMetrics(
            weekStart = LocalDate.of(2026, 9, 28),
            counters = mapOf("format" to 1L, "created.widget" to 3L, "active.days" to 5L),
            values = mapOf("plan.done_share" to 0.75),
        )
    }
}
