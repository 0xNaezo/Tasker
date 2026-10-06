package app.tasker.core.ai

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.data.ai.AiCommands
import app.tasker.core.data.ai.EnrichmentJob
import app.tasker.core.data.effects.CommitInfo
import app.tasker.core.data.effects.CommitListener
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.TaskId
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Starts and stops the background enrichment queue (CAP-7, tech plan §9.6, §12.3). */
interface EnrichmentScheduler {
    /** Makes sure the queue is processed once the device is online; cheap to call often. */
    suspend fun enqueue()

    /** Stops the queue, including a run in progress (turning AI off, SET-3). */
    suspend fun cancel()
}

/**
 * One unique WorkManager job drains the whole queue ([EnrichmentWorker]): sequential requests are gentle on rate
 * limits, and the job runs only with a network connection, retrying with exponential backoff.
 */
@Singleton
class WorkManagerEnrichmentScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : EnrichmentScheduler {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override suspend fun enqueue() {
        val states = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first().map { it.state }
        workManager.enqueueUniqueWork(WORK_NAME, policyFor(states), request()).await()
    }

    override suspend fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME).await()
    }

    companion object {
        const val WORK_NAME = "ai-enrichment"
        const val TAG = "ai-enrichment"
        val BACKOFF: Duration = Duration.ofSeconds(30)

        /**
         * Tasks are queued after their commit, so a run that has not started yet will see them: keep it. A running pass
         * may have read the queue already, so a follow-up run is chained after it.
         */
        internal fun policyFor(states: Collection<WorkInfo.State>): ExistingWorkPolicy = when {
            states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> ExistingWorkPolicy.KEEP
            states.any { it == WorkInfo.State.RUNNING } -> ExistingWorkPolicy.APPEND_OR_REPLACE
            else -> ExistingWorkPolicy.KEEP
        }

        internal fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<EnrichmentWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF)
            .addTag(TAG)
            .build()
    }
}

/** Queues enrichment after a commit that made tasks pending, when AI can run (CAP-7). */
class EnrichmentCommitListener @Inject constructor(
    private val gateways: AiGatewayProvider,
    private val scheduler: EnrichmentScheduler,
) : CommitListener {
    override suspend fun onCommitted(info: CommitInfo) {
        if (info.enrichTaskIds.isEmpty()) return
        if (gateways.isReady()) scheduler.enqueue()
    }
}

/** How one pass over the queue ended. */
enum class EnrichmentOutcome {
    /** Nothing left to do. */
    DONE,

    /** Some tasks hit a temporary failure (network, rate limit, overload): run again with backoff. */
    RETRY,

    /** AI is off, has no mode or no key, or access was denied: the tasks stay pending until AI can run again. */
    STOPPED,

    /** The pass reached its size limit; a follow-up run continues. */
    CONTINUE_LATER,
}

/**
 * One pass over the enrichment queue (tech plan §9.6, §17.1): builds each request, calls the gateway in effect and
 * applies the answer with `actor = AI`. [AiCommands.apply] checks the text hash and the field provenance, so passes
 * are idempotent and a result for edited text is dropped.
 *
 * - Success: fields are filled (an empty answer just marks the task done).
 * - Refusal or a failure that retrying cannot fix: the task is marked failed and its fields stay empty (§17.4).
 * - Temporary failure: the task stays pending; after two in a row the pass ends and is retried with backoff. After
 *   [MAX_ATTEMPTS] runs the task is given up, so one task can never keep the queue busy forever.
 * - [FailureKind.AUTH] (no key, invalid key, install rejected): the pass stops and the queue is kept for later; this
 *   is a problem of the setup, not of the task.
 * - AI turned off meanwhile: the pass stops at once.
 */
class EnrichmentProcessor @Inject constructor(
    private val commands: AiCommands,
    private val gateways: AiGatewayProvider,
    private val requests: EnrichRequestFactory,
    private val clock: DayClock,
) {
    /**
     * [attempt] is the run attempt of the job, 0 for the first run. The queue is read again after each round, so tasks
     * queued meanwhile are handled in the same run; a task whose text was edited while its request was out (the
     * answer was dropped as stale) is sent again with the new text.
     */
    suspend fun run(attempt: Int): EnrichmentOutcome {
        val tried = HashSet<Pair<TaskId, String>>()
        var retryLater = false
        var temporaryInRow = 0
        while (true) {
            val jobs = commands.pendingJobs().filter { it.key !in tried }.sortedBy { it.task.createdAt }
            if (jobs.isEmpty()) break
            for (job in jobs) {
                val gateway = gateways.active()
                if (gateway is DisabledAiGateway) return EnrichmentOutcome.STOPPED
                if (tried.size >= MAX_REQUESTS_PER_RUN) return if (retryLater) EnrichmentOutcome.RETRY else EnrichmentOutcome.CONTINUE_LATER
                tried += job.key
                when (process(gateway, job, attempt)) {
                    Step.COMPLETED -> temporaryInRow = 0
                    Step.TEMPORARY -> {
                        retryLater = true
                        temporaryInRow++
                        if (temporaryInRow >= MAX_TEMPORARY_IN_ROW) return EnrichmentOutcome.RETRY
                    }
                    Step.NO_ACCESS -> return EnrichmentOutcome.STOPPED
                }
            }
        }
        return if (retryLater) EnrichmentOutcome.RETRY else EnrichmentOutcome.DONE
    }

    private suspend fun process(gateway: AiGateway, job: EnrichmentJob, attempt: Int): Step {
        val taskId = job.task.id
        return when (val result = gateway.enrich(requests.build(job.task))) {
            is RouteResult.Success -> {
                commands.apply(taskId, job.textHash, EnrichResponseMapper.toFill(result.value, clock.zone()))
                Step.COMPLETED
            }
            is RouteResult.Refused -> {
                commands.markFailed(taskId)
                Step.COMPLETED
            }
            is RouteResult.Failed -> when {
                result.kind == FailureKind.AUTH -> Step.NO_ACCESS
                result.kind.retryable && attempt < MAX_ATTEMPTS -> Step.TEMPORARY
                else -> {
                    commands.markFailed(taskId)
                    Step.COMPLETED
                }
            }
        }
    }

    private val EnrichmentJob.key: Pair<TaskId, String> get() = task.id to textHash

    private enum class Step { COMPLETED, TEMPORARY, NO_ACCESS }

    companion object {
        /** Requests per run: keeps a run well within WorkManager's 10-minute limit. */
        const val MAX_REQUESTS_PER_RUN = 20

        /** Temporary failures in a row that end the run: the provider or the network is the problem, not the task. */
        const val MAX_TEMPORARY_IN_ROW = 2

        /** Runs before a task with temporary failures is given up: with the backoff doubling from 30 s, about 8 hours. */
        const val MAX_ATTEMPTS = 10
    }
}

/** Background enrichment (CAP-7): drains the queue with [EnrichmentProcessor]. Runs only with a network connection. */
@HiltWorker
class EnrichmentWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: EnrichmentProcessor,
    private val scheduler: EnrichmentScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (processor.run(runAttemptCount)) {
        EnrichmentOutcome.DONE, EnrichmentOutcome.STOPPED -> Result.success()
        EnrichmentOutcome.RETRY -> Result.retry()
        EnrichmentOutcome.CONTINUE_LATER -> {
            scheduler.enqueue()
            Result.success()
        }
    }
}
