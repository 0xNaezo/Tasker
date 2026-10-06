package app.tasker.core.ai

import android.content.Context
import androidx.core.content.edit
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import app.tasker.core.data.di.ApplicationScope
import app.tasker.core.data.metrics.MetricsCalculator
import app.tasker.core.data.metrics.WeeklyMetrics
import app.tasker.core.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** How a weekly telemetry report went. */
enum class MetricsDelivery {
    /** The backend took it; for [MetricsReporter.report]: every week due was sent, or none was due. */
    SENT,

    /** A temporary failure (network, overload, limit): worth another try soon. */
    RETRY,

    /** The backend or the install check refused it; the next daily run tries again. */
    REJECTED,

    /** Telemetry is off: no consent, or no backend in this build. */
    OFF,
}

/** Sends one week of aggregates to the backend; [ProxyAiGateway] in builds with the proxy. */
fun interface MetricsSender {
    suspend fun sendMetrics(metrics: WeeklyMetrics): MetricsDelivery
}

/** The last week sent. It describes this install rather than the user's data, so it lives outside the database. */
@Singleton
class SentMetricsWeeks @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun last(): LocalDate? = prefs.getString(KEY_LAST, null)?.let(LocalDate::parse)

    fun markSent(week: LocalDate) = prefs.edit { putString(KEY_LAST, week.toString()) }

    fun forget() = prefs.edit { remove(KEY_LAST) }

    private companion object {
        const val PREFS = "telemetry"
        const val KEY_LAST = "last_sent_week"
    }
}

/**
 * Weekly telemetry (tech plan §23). The metrics are computed on the device ([MetricsCalculator]); only aggregates of
 * finished weeks leave it, once per week, with the telemetry consent (the switch shared with crash reports, separate
 * from the AI consent) and only in builds with the backend.
 *
 * - Weeks before the app was first used are not reported, so the first report marks the week of the install.
 * - After the phone was off or offline, at most [MAX_CATCH_UP_WEEKS] weeks are caught up, oldest first.
 * - A new consent starts with the last full week; [MetricsScheduler] forgets the sent weeks when it is withdrawn,
 *   so a pause in the consent is never filled in later.
 */
@Singleton
class MetricsReporter @Inject constructor(
    private val environment: AiEnvironment,
    private val settings: SettingsRepository,
    private val calculator: MetricsCalculator,
    private val sender: MetricsSender,
    private val sent: SentMetricsWeeks,
) {
    /** The build has a backend to report to. */
    val available: Boolean get() = !environment.proxyBaseUrl.isNullOrBlank()

    /** Sends the weeks due; stops at the first week that was not taken and reports why. */
    suspend fun report(): MetricsDelivery {
        if (!available || !settings.settings.first().telemetryConsent) return MetricsDelivery.OFF
        val firstWeek = calculator.firstWeek() ?: return MetricsDelivery.SENT
        val lastWeek = calculator.lastFullWeek()
        val next = sent.last()?.plusWeeks(1) ?: lastWeek
        var week = listOf(firstWeek, next, lastWeek.minusWeeks(MAX_CATCH_UP_WEEKS - 1)).maxBy(LocalDate::toEpochDay)
        while (!week.isAfter(lastWeek)) {
            val delivery = sender.sendMetrics(calculator.week(week))
            if (delivery != MetricsDelivery.SENT) return delivery
            sent.markSent(week)
            week = week.plusWeeks(1)
        }
        return MetricsDelivery.SENT
    }

    companion object {
        const val MAX_CATCH_UP_WEEKS = 4L
    }
}

/** Runs [MetricsReporter] once a day with a network connection; a temporary failure is retried with backoff. */
@HiltWorker
class MetricsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reporter: MetricsReporter,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (reporter.report()) {
        MetricsDelivery.RETRY -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        MetricsDelivery.SENT, MetricsDelivery.REJECTED, MetricsDelivery.OFF -> Result.success()
    }

    private companion object {
        /** Retries within one day; the next daily run starts over. */
        const val MAX_RETRIES = 3
    }
}

/**
 * Keeps the daily telemetry job in step with the consent: scheduled while it is on in a build with the backend,
 * cancelled when it is withdrawn. Withdrawing also forgets the sent weeks (see [MetricsReporter]).
 */
@Singleton
class MetricsScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val reporter: MetricsReporter,
    private val sent: SentMetricsWeeks,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** Follows the consent for the life of the process. */
    fun start() {
        if (!reporter.available) return
        scope.launch {
            settings.settings.map { it.telemetryConsent }.distinctUntilChanged().collect { consent ->
                if (consent) {
                    workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request()).await()
                } else {
                    workManager.cancelUniqueWork(WORK_NAME).await()
                    sent.forget()
                }
            }
        }
    }

    companion object {
        const val WORK_NAME = "telemetry"
        val INTERVAL: Duration = Duration.ofDays(1)
        private val BACKOFF: Duration = Duration.ofMinutes(15)

        internal fun request(): PeriodicWorkRequest = PeriodicWorkRequestBuilder<MetricsWorker>(INTERVAL)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF)
            .build()
    }
}
