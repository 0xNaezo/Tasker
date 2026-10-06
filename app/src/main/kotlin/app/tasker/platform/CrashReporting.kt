package app.tasker.platform

import android.content.Context
import app.tasker.BuildConfig
import app.tasker.core.data.di.ApplicationScope
import app.tasker.core.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Crash reports (tech plan §5, ADR 0004): Sentry runs only when the build carries a DSN and the user turned reports on
 * in settings; turning them off closes it. Reports never contain task text (§21): messages, exception values and
 * breadcrumbs are dropped before sending, so only types and stack traces leave the phone.
 */
@Singleton
class CrashReporting @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        if (BuildConfig.SENTRY_DSN.isBlank()) return
        scope.launch {
            settings.settings.map { it.telemetryConsent }.distinctUntilChanged().collect { consent ->
                if (consent) enable() else Sentry.close()
            }
        }
    }

    private fun enable() {
        SentryAndroid.init(context) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.environment = "${BuildConfig.CHANNEL}-${BuildConfig.BUILD_TYPE}"
            options.isSendDefaultPii = false
            options.maxBreadcrumbs = 0
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> event.withoutUserText() }
        }
    }
}

/** Exception messages and log lines may quote a task; only the shape of the failure is kept. */
internal fun SentryEvent.withoutUserText(): SentryEvent = apply {
    message = null
    exceptions?.forEach { it.value = null }
    breadcrumbs = null
    user = null
    extras?.clear()
}
