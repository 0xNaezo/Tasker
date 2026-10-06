package app.tasker.core.ai

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import app.tasker.core.data.effects.CommitListener
import com.google.common.truth.Truth.assertThat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real Hilt graph of core:data and core:ai with only [AiEnvironment] supplied, as the app module does: every
 * binding resolves, the queue listener joins the commit listeners and WorkManager's Hilt factory builds the worker.
 */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class AiGraphTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var controller: AiController

    @Inject
    lateinit var gateway: AiGateway

    @Inject
    lateinit var listeners: Set<@JvmSuppressWildcards CommitListener>

    @Inject
    lateinit var scheduler: EnrichmentScheduler

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Module
    @InstallIn(SingletonComponent::class)
    object TestEnvironmentModule {
        @Provides
        fun environment(): AiEnvironment = AiEnvironment(directAvailable = true, proxyAvailable = true, proxyBaseUrl = "https://proxy.test")
    }

    @Test
    fun `the graph is complete`() {
        hilt.inject()

        assertThat(gateway).isInstanceOf(AiGatewayProvider::class.java)
        assertThat(scheduler).isInstanceOf(WorkManagerEnrichmentScheduler::class.java)
        assertThat(listeners.filterIsInstance<EnrichmentCommitListener>()).hasSize(1)
        val worker = TestListenableWorkerBuilder<EnrichmentWorker>(ApplicationProvider.getApplicationContext<Context>())
            .setWorkerFactory(workerFactory)
            .build()
        assertThat(worker).isInstanceOf(EnrichmentWorker::class.java)
    }
}
