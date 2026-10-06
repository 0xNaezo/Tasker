package app.tasker.core.ai.di

import android.content.Context
import app.tasker.core.ai.AiGateway
import app.tasker.core.ai.AiGatewayProvider
import app.tasker.core.ai.AiProxyHttpClient
import app.tasker.core.ai.DirectAiGateway
import app.tasker.core.ai.DirectGateway
import app.tasker.core.ai.EnrichmentCommitListener
import app.tasker.core.ai.EnrichmentScheduler
import app.tasker.core.ai.IntegrityTokenProvider
import app.tasker.core.ai.PlayIntegrityTokenProvider
import app.tasker.core.ai.ProxyAiGateway
import app.tasker.core.ai.ProxyGateway
import app.tasker.core.ai.SecretVault
import app.tasker.core.ai.TinkKeystoreAead
import app.tasker.core.ai.WorkManagerEnrichmentScheduler
import app.tasker.core.data.di.IoDispatcher
import app.tasker.core.data.effects.CommitListener
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Bindings of `core:ai`. The app module must provide [app.tasker.core.ai.AiEnvironment] (see its KDoc) and give
 * WorkManager the `HiltWorkerFactory`, so that [app.tasker.core.ai.EnrichmentWorker] gets its dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AiBindingsModule {
    /** Domain-facing gateway: routes every call to the gateway in effect. */
    @Binds
    abstract fun aiGateway(provider: AiGatewayProvider): AiGateway

    @Binds
    @DirectGateway
    abstract fun directGateway(gateway: DirectAiGateway): AiGateway

    @Binds
    @ProxyGateway
    abstract fun proxyGateway(gateway: ProxyAiGateway): AiGateway

    @Binds
    abstract fun integrityTokenProvider(provider: PlayIntegrityTokenProvider): IntegrityTokenProvider

    @Binds
    abstract fun enrichmentScheduler(scheduler: WorkManagerEnrichmentScheduler): EnrichmentScheduler

    @Binds
    @IntoSet
    abstract fun enrichmentCommitListener(listener: EnrichmentCommitListener): CommitListener
}

@Module
@InstallIn(SingletonComponent::class)
object AiModule {
    @Provides
    @Singleton
    fun secretVault(@ApplicationContext context: Context, @IoDispatcher io: CoroutineDispatcher): SecretVault =
        SecretVault(File(context.noBackupFilesDir, SecretVault.DIRECTORY), { TinkKeystoreAead.create(context) }, io)

    /** Generous timeouts: the backend itself waits for the provider with retries (§18.1). No logging plugin (§21). */
    @Provides
    @Singleton
    @AiProxyHttpClient
    fun proxyHttpClient(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val SOCKET_TIMEOUT_MS = 150_000L
    private const val REQUEST_TIMEOUT_MS = 180_000L
}
