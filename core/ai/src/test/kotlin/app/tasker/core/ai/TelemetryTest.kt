package app.tasker.core.ai

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.tasker.core.data.maintenance.MaintenanceRunner
import app.tasker.core.data.metrics.MetricsCalculator
import app.tasker.core.data.metrics.WeeklyMetrics
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TelemetryTest {
    // Tuesday 2026-10-06: the last full week starts on Monday 2026-09-28.
    private val env = AiTestEnv(settings = CONSENT)
    private val maintenance = MaintenanceRunner(env.runner, env.clock, env.settings, env.db)
    private val sent = SentMetricsWeeks(env.context)
    private val sender = RecordingSender()

    @After
    fun tearDown() {
        sent.forget()
        env.close()
    }

    private fun reporter(environment: AiEnvironment = AiTestEnv.PROXY_ONLY) =
        MetricsReporter(environment, env.settings, MetricsCalculator(env.db, env.clock), sender, sent)

    @Test
    fun `the last full week is sent once`() = runTest {
        usedOn(LocalDate.of(2026, 9, 30))

        assertThat(reporter().report()).isEqualTo(MetricsDelivery.SENT)
        assertThat(reporter().report()).isEqualTo(MetricsDelivery.SENT)

        assertThat(sender.weeks).containsExactly(LAST_WEEK)
        assertThat(sender.sent.single().counters["active.days"]).isEqualTo(1)
        assertThat(sent.last()).isEqualTo(LAST_WEEK)
    }

    @Test
    fun `nothing leaves the phone without the consent`() = runTest {
        usedOn(LocalDate.of(2026, 9, 30))
        env.settings.update { it.copy(telemetryConsent = false) }

        assertThat(reporter().report()).isEqualTo(MetricsDelivery.OFF)
        assertThat(sender.weeks).isEmpty()
    }

    @Test
    fun `a build without the backend reports nothing`() = runTest {
        usedOn(LocalDate.of(2026, 9, 30))

        assertThat(reporter(AiTestEnv.DIRECT_ONLY).report()).isEqualTo(MetricsDelivery.OFF)
        assertThat(sender.weeks).isEmpty()
    }

    @Test
    fun `weeks before the first use are not reported`() = runTest {
        assertThat(reporter().report()).isEqualTo(MetricsDelivery.SENT)
        usedOn(LocalDate.of(2026, 10, 5))
        assertThat(reporter().report()).isEqualTo(MetricsDelivery.SENT)

        assertThat(sender.weeks).isEmpty()
    }

    @Test
    fun `missed weeks are caught up, oldest first and at most four`() = runTest {
        usedOn(LocalDate.of(2026, 8, 3))
        sent.markSent(LocalDate.of(2026, 8, 3))

        reporter().report()

        assertThat(sender.weeks).containsExactly(
            LocalDate.of(2026, 9, 7),
            LocalDate.of(2026, 9, 14),
            LocalDate.of(2026, 9, 21),
            LAST_WEEK,
        ).inOrder()
    }

    @Test
    fun `a failed week stops the run and goes first next time`() = runTest {
        usedOn(LocalDate.of(2026, 9, 1))
        sent.markSent(LocalDate.of(2026, 9, 14))
        sender.answers += MetricsDelivery.SENT
        sender.answers += MetricsDelivery.RETRY

        assertThat(reporter().report()).isEqualTo(MetricsDelivery.RETRY)
        assertThat(sent.last()).isEqualTo(LocalDate.of(2026, 9, 21))

        assertThat(reporter().report()).isEqualTo(MetricsDelivery.SENT)
        assertThat(sender.weeks).containsExactly(LocalDate.of(2026, 9, 21), LAST_WEEK, LAST_WEEK).inOrder()
    }

    @Test
    fun `the worker retries temporary failures a few times within the day`() = runTest {
        usedOn(LocalDate.of(2026, 9, 30))
        sender.fallback = MetricsDelivery.RETRY

        assertThat(worker(attempt = 0).doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(worker(attempt = 3).doWork()).isEqualTo(ListenableWorker.Result.success())
        sender.fallback = MetricsDelivery.REJECTED
        assertThat(worker(attempt = 0).doWork()).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `the daily job follows the consent, and withdrawing it forgets the sent weeks`() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            env.context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setMinimumLoggingLevel(Log.ERROR).build(),
        )
        val workManager = WorkManager.getInstance(env.context)
        val scheduler = MetricsScheduler(env.context, env.settings, reporter(), sent, backgroundScope)

        scheduler.start()
        runCurrent()
        val info = workManager.getWorkInfosForUniqueWork(MetricsScheduler.WORK_NAME).get().single()
        assertThat(info.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        val spec = MetricsScheduler.request().workSpec
        assertThat(spec.isPeriodic).isTrue()
        assertThat(spec.intervalDuration).isEqualTo(TimeUnit.DAYS.toMillis(1))
        assertThat(spec.workerClassName).isEqualTo(MetricsWorker::class.java.name)

        sent.markSent(LAST_WEEK)
        env.settings.update { it.copy(telemetryConsent = false) }
        runCurrent()

        val cancelled = workManager.getWorkInfosForUniqueWork(MetricsScheduler.WORK_NAME).get().single()
        assertThat(cancelled.state).isEqualTo(WorkInfo.State.CANCELLED)
        assertThat(sent.last()).isNull()
    }

    @Test
    fun `a build without the backend schedules nothing`() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            env.context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setMinimumLoggingLevel(Log.ERROR).build(),
        )
        MetricsScheduler(env.context, env.settings, reporter(AiTestEnv.DIRECT_ONLY), sent, backgroundScope).start()
        runCurrent()

        assertThat(WorkManager.getInstance(env.context).getWorkInfosForUniqueWork(MetricsScheduler.WORK_NAME).get()).isEmpty()
    }

    /** The app was opened on [day]; the clock returns to the test's present afterwards. */
    private suspend fun usedOn(day: LocalDate) {
        val now = env.time.now()
        env.time.set(day.atTime(12, 0))
        maintenance.recordActivity()
        env.time.set(now)
    }

    private fun worker(attempt: Int): MetricsWorker {
        val reporter = reporter()
        return TestListenableWorkerBuilder<MetricsWorker>(env.context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        MetricsWorker(appContext, workerParameters, reporter)
                },
            )
            .build()
    }

    private class RecordingSender : MetricsSender {
        val sent = mutableListOf<WeeklyMetrics>()
        val answers = ArrayDeque<MetricsDelivery>()
        var fallback = MetricsDelivery.SENT
        val weeks: List<LocalDate> get() = sent.map { it.weekStart }

        override suspend fun sendMetrics(metrics: WeeklyMetrics): MetricsDelivery {
            sent += metrics
            return answers.removeFirstOrNull() ?: fallback
        }
    }

    private companion object {
        val CONSENT = AiTestEnv.AI_OFF.copy(telemetryConsent = true)
        val LAST_WEEK: LocalDate = LocalDate.of(2026, 9, 28)
    }
}
