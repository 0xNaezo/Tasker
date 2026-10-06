package app.tasker.core.ai

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.data.effects.CommitInfo
import app.tasker.core.model.Actor
import app.tasker.core.model.EnrichState
import app.tasker.core.model.Estimate
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EnrichmentWorkTest {
    private val env = AiTestEnv()
    private val dir: File = Files.createTempDirectory("vault").toFile()
    private val keys = ApiKeyStore(testVault(dir)).also { runBlocking { it.save("sk-ant-test") } }
    private val gateway = FakeGateway()
    private val provider = gatewayProvider(env, keys, direct = gateway)
    private val scheduler = FakeScheduler()

    @After
    fun tearDown() {
        env.close()
        dir.deleteRecursively()
    }

    private fun worker(attempt: Int = 0): EnrichmentWorker {
        val processor = EnrichmentProcessor(env.ai, provider, env.requests, env.clock)
        return TestListenableWorkerBuilder<EnrichmentWorker>(env.context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        EnrichmentWorker(appContext, workerParameters, processor, scheduler)
                },
            )
            .build()
    }

    @Test
    fun `worker applies answers and succeeds`() = runTest {
        val task = env.add("подготовить отчёт")
        gateway.fallback = success(EnrichResponse(estimate = "L"))

        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(env.task(task.id).estimate).isEqualTo(Estimate.L)
    }

    @Test
    fun `retryable failures make the worker retry, the task stays pending`() = runTest {
        val task = env.add("подготовить отчёт")
        gateway.fallback = RouteResult.Failed(FailureKind.RATE_LIMIT)

        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(env.task(task.id).enrichState).isEqualTo(EnrichState.PENDING)
    }

    @Test
    fun `non-retryable failures mark the task failed and the worker succeeds`() = runTest {
        val task = env.add("подготовить отчёт")
        gateway.fallback = RouteResult.Failed(FailureKind.TRUNCATED)

        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(env.task(task.id).enrichState).isEqualTo(EnrichState.FAILED)
    }

    @Test
    fun `the last attempt gives retryable failures up`() = runTest {
        val task = env.add("подготовить отчёт")
        gateway.fallback = RouteResult.Failed(FailureKind.NETWORK)

        assertThat(worker(attempt = EnrichmentProcessor.MAX_ATTEMPTS).doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(env.task(task.id).enrichState).isEqualTo(EnrichState.FAILED)
    }

    @Test
    fun `with AI off the worker stops without calls`() = runTest {
        env.add("подготовить отчёт")
        env.settings.update { it.copy(ai = it.ai.copy(enabled = false)) }

        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(gateway.requests).isEmpty()
    }

    @Test
    fun `a long queue schedules a follow-up run`() = runTest {
        repeat(EnrichmentProcessor.MAX_REQUESTS_PER_RUN + 1) { env.add("задача номер $it") }

        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(scheduler.enqueued.get()).isEqualTo(1)
    }

    @Test
    fun `commit listener queues work only for new pending tasks while AI can run`() = runTest {
        val listener = EnrichmentCommitListener(provider, scheduler)
        val queued = commit(enrich = setOf("t1"))

        listener.onCommitted(commit(enrich = emptySet()))
        assertThat(scheduler.enqueued.get()).isEqualTo(0)

        listener.onCommitted(queued)
        assertThat(scheduler.enqueued.get()).isEqualTo(1)

        keys.clear()
        listener.onCommitted(queued)
        assertThat(scheduler.enqueued.get()).isEqualTo(1)

        keys.save("sk-ant-test")
        env.settings.update { it.copy(ai = it.ai.copy(enabled = false)) }
        listener.onCommitted(queued)
        assertThat(scheduler.enqueued.get()).isEqualTo(1)
    }

    @Test
    fun `the scheduler keeps one unique job with a network constraint and exponential backoff`() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            env.context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setMinimumLoggingLevel(Log.ERROR).build(),
        )
        val workManager = WorkManager.getInstance(env.context)
        val scheduler = WorkManagerEnrichmentScheduler(env.context)

        scheduler.enqueue()
        scheduler.enqueue()

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerEnrichmentScheduler.WORK_NAME).get()
        val info = infos.single()
        assertThat(info.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(info.tags).contains(WorkManagerEnrichmentScheduler.TAG)
        assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        val spec = WorkManagerEnrichmentScheduler.request().workSpec
        assertThat(spec.backoffPolicy).isEqualTo(BackoffPolicy.EXPONENTIAL)
        assertThat(spec.backoffDelayDuration).isEqualTo(TimeUnit.SECONDS.toMillis(30))
        assertThat(spec.workerClassName).isEqualTo(EnrichmentWorker::class.java.name)

        scheduler.cancel()

        val cancelled = workManager.getWorkInfosForUniqueWork(WorkManagerEnrichmentScheduler.WORK_NAME).get().single()
        assertThat(cancelled.state).isEqualTo(WorkInfo.State.CANCELLED)
    }

    @Test
    fun `a running pass gets a follow-up, a pass that has not started yet sees new tasks anyway`() {
        val policy = { states: List<WorkInfo.State> -> WorkManagerEnrichmentScheduler.policyFor(states) }
        assertThat(policy(emptyList())).isEqualTo(ExistingWorkPolicy.KEEP)
        assertThat(policy(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED))).isEqualTo(ExistingWorkPolicy.KEEP)
        assertThat(policy(listOf(WorkInfo.State.ENQUEUED))).isEqualTo(ExistingWorkPolicy.KEEP)
        assertThat(policy(listOf(WorkInfo.State.RUNNING))).isEqualTo(ExistingWorkPolicy.APPEND_OR_REPLACE)
        assertThat(policy(listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED))).isEqualTo(ExistingWorkPolicy.KEEP)
    }

    private fun commit(enrich: Set<String>) = CommitInfo(
        batchId = "batch",
        actor = Actor.USER,
        taskIds = enrich,
        projectIds = emptySet(),
        planDates = emptySet(),
        reminderTaskIds = emptySet(),
        enrichTaskIds = enrich,
    )
}
