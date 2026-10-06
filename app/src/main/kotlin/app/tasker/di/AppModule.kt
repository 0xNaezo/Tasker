package app.tasker.di

import app.tasker.BuildConfig
import app.tasker.core.ai.AiEnvironment
import app.tasker.core.data.settings.TelemetryOptions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /**
     * AI access follows the install channel (tech plan §17.2, §24.1): builds from GitHub use the user's own API key,
     * builds from Google Play use the app's proxy. A Play build without a proxy URL simply offers no AI.
     */
    @Provides
    @Singleton
    fun aiEnvironment(): AiEnvironment = when (BuildConfig.CHANNEL) {
        "play" -> AiEnvironment(
            directAvailable = false,
            proxyAvailable = BuildConfig.AI_PROXY_URL.isNotBlank(),
            proxyBaseUrl = BuildConfig.AI_PROXY_URL.ifBlank { null },
            integrityCloudProjectNumber = BuildConfig.PLAY_CLOUD_PROJECT.takeIf { it > 0 },
            proxyDevInstallKey = BuildConfig.AI_PROXY_DEV_KEY.ifBlank { null },
        )
        else -> AiEnvironment(directAvailable = true, proxyAvailable = false, proxyBaseUrl = null)
    }

    /** Crash reports need a Sentry DSN in the build; weekly statistics go to the backend of the AI proxy (§23). */
    @Provides
    fun telemetryOptions(ai: AiEnvironment): TelemetryOptions =
        TelemetryOptions(crashReports = BuildConfig.SENTRY_DSN.isNotBlank(), statistics = !ai.proxyBaseUrl.isNullOrBlank())
}
