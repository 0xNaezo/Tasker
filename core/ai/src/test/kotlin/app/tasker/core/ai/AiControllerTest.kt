package app.tasker.core.ai

import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.data.ai.AiFill
import app.tasker.core.model.AiMode
import app.tasker.core.model.AppSettings
import app.tasker.core.model.EnrichState
import app.tasker.core.model.Estimate
import app.tasker.core.model.FieldSource
import app.tasker.core.model.TaskField
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AiControllerTest {
    private val dir: File = Files.createTempDirectory("vault").toFile()
    private val vault = testVault(dir)
    private val direct = FakeGateway()
    private val proxy = FakeGateway()
    private val scheduler = FakeScheduler()
    private lateinit var env: AiTestEnv
    private lateinit var keys: ApiKeyStore
    private lateinit var controller: AiController

    private fun setUp(settings: AppSettings = AiTestEnv.AI_OFF, environment: AiEnvironment = AiTestEnv.DIRECT_ONLY) {
        env = AiTestEnv(settings)
        keys = ApiKeyStore(vault)
        val gateways = gatewayProvider(env, keys, direct, proxy, environment)
        controller = AiController(env.settings, keys, gateways, env.ai, scheduler, env.requests, env.repo, env.clock)
    }

    @After
    fun tearDown() {
        env.close()
        dir.deleteRecursively()
    }

    private suspend fun state(): AiState = controller.state.first()

    @Test
    fun `AI is off and without consent until the user decides`() = runTest {
        setUp()

        assertThat(state()).isEqualTo(
            AiState(
                enabled = false,
                hasConsent = false,
                mode = null,
                availableModes = setOf(AiMode.DIRECT),
                hasApiKey = false,
                pendingCount = 0,
            ),
        )
        assertThat(state().isActive).isFalse()
        // Without AI, captured tasks never wait for enrichment.
        assertThat(env.add("позвонить в банк").enrichState).isEqualTo(EnrichState.NONE)
    }

    @Test
    fun `consent records the time, turns AI on and picks the mode, the direct mode then waits for a key`() = runTest {
        setUp()

        controller.giveConsent(AiMode.DIRECT)

        val ai = env.settings.current().ai
        assertThat(ai.enabled).isTrue()
        assertThat(ai.mode).isEqualTo(AiMode.DIRECT)
        assertThat(ai.consentAtEpochMillis).isEqualTo(env.clock.now().toEpochMilli())
        assertThat(state().hasConsent).isTrue()
        assertThat(state().isActive).isFalse()
        assertThat(scheduler.enqueued.get()).isEqualTo(0)

        controller.saveApiKey("  sk-or-v1-secret  ")

        assertThat(state().hasApiKey).isTrue()
        assertThat(state().isActive).isTrue()
        assertThat(keys.get()).isEqualTo("sk-or-v1-secret")
        assertThat(scheduler.enqueued.get()).isEqualTo(1)
    }

    @Test
    fun `consent in the proxy mode starts the queue at once`() = runTest {
        setUp(environment = AiTestEnv.PROXY_ONLY)

        controller.giveConsent(AiMode.PROXY)

        assertThat(state().isActive).isTrue()
        assertThat(state().availableModes).containsExactly(AiMode.PROXY)
        assertThat(scheduler.enqueued.get()).isEqualTo(1)
    }

    @Test
    fun `a mode this build does not offer is rejected`() = runTest {
        setUp()

        val error = runCatching { controller.giveConsent(AiMode.PROXY) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(env.settings.current().ai.hasConsent).isFalse()
    }

    @Test
    fun `a mode chosen in a build of another channel counts as none`() = runTest {
        setUp(settings = AiTestEnv.AI_ON.copy(ai = AiTestEnv.AI_ON.ai.copy(mode = AiMode.PROXY)))

        assertThat(state().mode).isNull()
        assertThat(state().isActive).isFalse()
    }

    @Test
    fun `turning AI off cancels the queue and the work, filled fields keep their AI mark`() = runTest {
        setUp(settings = AiTestEnv.AI_ON)
        controller.saveApiKey("sk-or-v1-test")
        val filled = env.add("подготовить отчёт")
        val waiting = env.add("позвонить в банк")
        env.ai.apply(filled.id, checkNotNull(env.ai.job(filled.id)).textHash, AiFill(estimate = Estimate.L))
        assertThat(state().pendingCount).isEqualTo(1)

        controller.setEnabled(false)

        assertThat(scheduler.cancelled.get()).isEqualTo(1)
        assertThat(env.task(waiting.id).enrichState).isEqualTo(EnrichState.NONE)
        assertThat(env.task(filled.id).estimate).isEqualTo(Estimate.L)
        assertThat(env.task(filled.id).fieldSources[TaskField.ESTIMATE]).isEqualTo(FieldSource.AI)
        with(state()) {
            assertThat(enabled).isFalse()
            assertThat(hasConsent).isTrue()
            assertThat(pendingCount).isEqualTo(0)
            assertThat(isActive).isFalse()
        }
        assertThat(env.add("новая задача").enrichState).isEqualTo(EnrichState.NONE)
    }

    @Test
    fun `turning AI on again needs no new consent`() = runTest {
        setUp(settings = AiTestEnv.AI_ON)
        controller.saveApiKey("sk-or-v1-test")
        controller.setEnabled(false)
        val enqueued = scheduler.enqueued.get()

        controller.setEnabled(true)

        assertThat(state().isActive).isTrue()
        assertThat(scheduler.enqueued.get()).isEqualTo(enqueued + 1)
        assertThat(env.add("новая задача").enrichState).isEqualTo(EnrichState.PENDING)
    }

    @Test
    fun `the switch alone does not give consent`() = runTest {
        setUp()

        controller.setEnabled(true)

        assertThat(state().enabled).isTrue()
        assertThat(state().isActive).isFalse()
        assertThat(scheduler.enqueued.get()).isEqualTo(0)
        assertThat(env.add("позвонить в банк").enrichState).isEqualTo(EnrichState.NONE)
    }

    @Test
    fun `not now on the consent card is remembered`() = runTest {
        setUp()

        controller.dismissConsentPrompt()

        assertThat(state().consentPromptDismissed).isTrue()
        assertThat(state().enabled).isFalse()
        assertThat(state().hasConsent).isFalse()
    }

    @Test
    fun `pending count follows the queue`() = runTest {
        setUp(settings = AiTestEnv.AI_ON)

        env.add("первая")
        env.add("вторая")

        assertThat(state().pendingCount).isEqualTo(2)
    }

    @Test
    fun `the key is stored encrypted and deleted with one call`() = runTest {
        setUp(settings = AiTestEnv.AI_ON)

        controller.saveApiKey("sk-or-v1-very-secret")

        val stored = dir.listFiles().orEmpty().filter { it.isFile }
        assertThat(stored).isNotEmpty()
        stored.forEach { file -> assertThat(String(file.readBytes(), Charsets.ISO_8859_1)).doesNotContain("very-secret") }

        controller.clearApiKey()

        assertThat(state().hasApiKey).isFalse()
        assertThat(keys.get()).isNull()
        assertThat(dir.listFiles().orEmpty().filter { it.isFile }).isEmpty()
        assertThat(scheduler.cancelled.get()).isEqualTo(1)
    }

    @Test
    fun `malformed keys are rejected`() = runTest {
        setUp(settings = AiTestEnv.AI_ON)

        val bad = listOf("", "   ", "sk-or- v1", "sk-or-\nv1", "sk-or-v1-ключ", "x".repeat(ApiKeyStore.MAX_LENGTH + 1))
        for (key in bad) {
            assertThat(runCatching { controller.saveApiKey(key) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(keys.get()).isNull()
        // Other formats than sk-or-… are accepted (new key types).
        controller.saveApiKey("custom-key-123")
        assertThat(keys.get()).isEqualTo("custom-key-123")
        assertThat(ApiKeyStore.looksLikeOpenRouterKey("custom-key-123")).isFalse()
        assertThat(ApiKeyStore.looksLikeOpenRouterKey(" sk-or-v1-x")).isTrue()
    }

    @Test
    fun `connection check sends a fixed text and maps the answer`() = runTest {
        setUp(settings = AiTestEnv.AI_ON)
        controller.saveApiKey("sk-or-v1-test")
        env.add("секретный проект")

        direct.fallback = success(EnrichResponse(estimate = "S"))
        assertThat(controller.checkConnection()).isEqualTo(ConnectionCheck.Ok)
        direct.fallback = RouteResult.Refused("cyber")
        assertThat(controller.checkConnection()).isEqualTo(ConnectionCheck.Refused)
        direct.fallback = RouteResult.Failed(FailureKind.NETWORK)
        assertThat(controller.checkConnection()).isEqualTo(ConnectionCheck.Failed(FailureKind.NETWORK))
        direct.fallback = RouteResult.Failed(FailureKind.AUTH)
        assertThat(controller.checkConnection()).isEqualTo(ConnectionCheck.Failed(FailureKind.AUTH))

        assertThat(direct.requests.map { it.text }.toSet()).containsExactly(EnrichRequestFactory.PROBE_TEXT)
    }

    @Test
    fun `connection check works with the switch off but needs a mode and, in the direct mode, a key`() = runTest {
        setUp()
        assertThat(controller.checkConnection()).isEqualTo(ConnectionCheck.Failed(FailureKind.AUTH))

        controller.giveConsent(AiMode.DIRECT)
        controller.setEnabled(false)
        assertThat(controller.checkConnection()).isEqualTo(ConnectionCheck.Failed(FailureKind.AUTH))
        assertThat(direct.requests).isEmpty()

        controller.saveApiKey("sk-or-v1-test")
        assertThat(controller.checkConnection()).isEqualTo(ConnectionCheck.Ok)
        assertThat(direct.requests).hasSize(1)
    }
}
