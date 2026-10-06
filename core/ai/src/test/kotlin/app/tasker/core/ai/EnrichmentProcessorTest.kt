package app.tasker.core.ai

import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.data.command.FieldUpdate
import app.tasker.core.data.command.TaskEdit
import app.tasker.core.model.EnrichState
import app.tasker.core.model.Estimate
import app.tasker.core.model.FieldSource
import app.tasker.core.model.TaskField
import app.tasker.core.testing.date
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EnrichmentProcessorTest {
    private val env = AiTestEnv()
    private val dir: File = Files.createTempDirectory("vault").toFile()
    private val keys = ApiKeyStore(testVault(dir)).also { runBlocking { it.save("sk-or-v1-test") } }
    private val gateway = FakeGateway()
    private val processor = EnrichmentProcessor(env.ai, gatewayProvider(env, keys, direct = gateway), env.requests, env.clock)

    @After
    fun tearDown() {
        env.close()
        dir.deleteRecursively()
    }

    @Test
    fun `an answer is applied with the AI mark and the task leaves the queue`() = runTest {
        val task = env.add("подготовить квартальный отчёт")
        gateway.fallback = success(EnrichResponse(estimate = "L", estimateConfidence = 0.7, planDate = "2026-10-08"))

        val outcome = processor.run(attempt = 0)

        assertThat(outcome).isEqualTo(EnrichmentOutcome.DONE)
        assertThat(gateway.requests.single().text).isEqualTo("подготовить квартальный отчёт")
        val filled = env.task(task.id)
        assertThat(filled.estimate).isEqualTo(Estimate.L)
        assertThat(filled.planDate).isEqualTo(date("2026-10-08"))
        assertThat(filled.fieldSources[TaskField.ESTIMATE]).isEqualTo(FieldSource.AI)
        assertThat(filled.enrichState).isEqualTo(EnrichState.DONE)
    }

    @Test
    fun `a refusal and a failure that retrying cannot fix mark the task failed`() = runTest {
        val refused = env.add("первая задача")
        val invalid = env.add("вторая задача")
        gateway.answer = { request ->
            if (request.text == "первая задача") RouteResult.Refused("cyber") else RouteResult.Failed(FailureKind.INVALID_OUTPUT)
        }

        val outcome = processor.run(attempt = 0)

        assertThat(outcome).isEqualTo(EnrichmentOutcome.DONE)
        assertThat(env.task(refused.id).enrichState).isEqualTo(EnrichState.FAILED)
        assertThat(env.task(invalid.id).enrichState).isEqualTo(EnrichState.FAILED)
        assertThat(env.task(invalid.id).estimate).isNull()
    }

    @Test
    fun `a temporary failure keeps the task pending and asks for a retry`() = runTest {
        val task = env.add("позвонить в банк")
        gateway.fallback = RouteResult.Failed(FailureKind.NETWORK)

        assertThat(processor.run(attempt = 0)).isEqualTo(EnrichmentOutcome.RETRY)
        assertThat(env.task(task.id).enrichState).isEqualTo(EnrichState.PENDING)
    }

    @Test
    fun `two temporary failures in a row end the run`() = runTest {
        repeat(3) { env.add("задача $it") }
        gateway.fallback = RouteResult.Failed(FailureKind.RATE_LIMIT)

        assertThat(processor.run(attempt = 0)).isEqualTo(EnrichmentOutcome.RETRY)
        assertThat(gateway.requests).hasSize(EnrichmentProcessor.MAX_TEMPORARY_IN_ROW)
    }

    @Test
    fun `a temporary failure of one task does not hold back the others`() = runTest {
        val stuck = env.add("первая")
        val second = env.add("вторая")
        val third = env.add("третья")
        gateway.answer = { request ->
            if (request.text == "первая") RouteResult.Failed(FailureKind.OVERLOADED) else success(EnrichResponse(estimate = "S"))
        }

        assertThat(processor.run(attempt = 0)).isEqualTo(EnrichmentOutcome.RETRY)
        assertThat(gateway.requests.map { it.text }).containsExactly("первая", "вторая", "третья").inOrder()
        assertThat(env.task(stuck.id).enrichState).isEqualTo(EnrichState.PENDING)
        assertThat(env.task(second.id).estimate).isEqualTo(Estimate.S)
        assertThat(env.task(third.id).estimate).isEqualTo(Estimate.S)
    }

    @Test
    fun `after the last attempt a temporary failure gives the task up`() = runTest {
        val task = env.add("позвонить в банк")
        gateway.fallback = RouteResult.Failed(FailureKind.NETWORK)

        assertThat(processor.run(attempt = EnrichmentProcessor.MAX_ATTEMPTS)).isEqualTo(EnrichmentOutcome.DONE)
        assertThat(env.task(task.id).enrichState).isEqualTo(EnrichState.FAILED)
    }

    @Test
    fun `no access stops the run and keeps the queue`() = runTest {
        val first = env.add("первая")
        val second = env.add("вторая")
        gateway.fallback = RouteResult.Failed(FailureKind.AUTH, detail = "invalid key")

        assertThat(processor.run(attempt = 0)).isEqualTo(EnrichmentOutcome.STOPPED)
        assertThat(gateway.requests).hasSize(1)
        assertThat(env.task(first.id).enrichState).isEqualTo(EnrichState.PENDING)
        assertThat(env.task(second.id).enrichState).isEqualTo(EnrichState.PENDING)
    }

    @Test
    fun `nothing is sent when AI is off`() = runTest {
        val task = env.add("позвонить в банк")
        env.settings.update { it.copy(ai = it.ai.copy(enabled = false)) }

        assertThat(processor.run(attempt = 0)).isEqualTo(EnrichmentOutcome.STOPPED)
        assertThat(gateway.requests).isEmpty()
        assertThat(env.task(task.id).enrichState).isEqualTo(EnrichState.PENDING)
    }

    @Test
    fun `nothing is sent in the direct mode without a key`() = runTest {
        env.add("позвонить в банк")
        keys.clear()

        assertThat(processor.run(attempt = 0)).isEqualTo(EnrichmentOutcome.STOPPED)
        assertThat(gateway.requests).isEmpty()
    }

    @Test
    fun `turning AI off during a run stops it before the next request`() = runTest {
        env.add("первая")
        val second = env.add("вторая")
        gateway.onCall = { env.settings.update { it.copy(ai = it.ai.copy(enabled = false)) } }

        assertThat(processor.run(attempt = 0)).isEqualTo(EnrichmentOutcome.STOPPED)
        assertThat(gateway.requests).hasSize(1)
        assertThat(env.task(second.id).enrichState).isEqualTo(EnrichState.PENDING)
    }

    @Test
    fun `an answer for edited text is dropped and the new text is sent`() = runTest {
        val task = env.add("написать письмо")
        var edited = false
        gateway.onCall = {
            if (!edited) {
                edited = true
                env.tasks.edit(task.id, TaskEdit(title = FieldUpdate.Set("написать два больших письма")))
            }
        }
        gateway.fallback = success(EnrichResponse(estimate = "M"))

        assertThat(processor.run(attempt = 0)).isEqualTo(EnrichmentOutcome.DONE)
        assertThat(gateway.requests.map { it.text }).containsExactly("написать письмо", "написать два больших письма").inOrder()
        assertThat(env.task(task.id).estimate).isEqualTo(Estimate.M)
    }

    @Test
    fun `tasks queued during a run are handled in the same run`() = runTest {
        env.add("первая")
        var added = false
        gateway.onCall = {
            if (!added) {
                added = true
                env.add("добавлена позже")
            }
        }

        assertThat(processor.run(attempt = 0)).isEqualTo(EnrichmentOutcome.DONE)
        assertThat(gateway.requests.map { it.text }).containsExactly("первая", "добавлена позже").inOrder()
        assertThat(env.ai.pendingJobs()).isEmpty()
    }

    @Test
    fun `a long queue continues in a follow-up run`() = runTest {
        repeat(EnrichmentProcessor.MAX_REQUESTS_PER_RUN + 1) { env.add("задача номер $it") }

        assertThat(processor.run(attempt = 0)).isEqualTo(EnrichmentOutcome.CONTINUE_LATER)
        assertThat(gateway.requests).hasSize(EnrichmentProcessor.MAX_REQUESTS_PER_RUN)
        assertThat(env.ai.pendingJobs()).hasSize(1)

        assertThat(processor.run(attempt = 0)).isEqualTo(EnrichmentOutcome.DONE)
        assertThat(env.ai.pendingJobs()).isEmpty()
    }

    @Test
    fun `the user's own value is never overwritten`() = runTest {
        val task = env.add("написать письмо")
        env.tasks.edit(task.id, TaskEdit(estimate = FieldUpdate.Set(Estimate.S)))
        gateway.fallback = success(EnrichResponse(estimate = "L"))

        processor.run(attempt = 0)

        assertThat(env.task(task.id).estimate).isEqualTo(Estimate.S)
        assertThat(env.task(task.id).fieldSources[TaskField.ESTIMATE]).isEqualTo(FieldSource.USER)
    }
}
