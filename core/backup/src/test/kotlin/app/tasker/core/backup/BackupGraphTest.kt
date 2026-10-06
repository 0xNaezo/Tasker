package app.tasker.core.backup

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Hilt graph the app gets: data, database and backup modules together, and the worker built by HiltWorkerFactory. */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class BackupGraphTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var export: ExportService

    @Inject
    lateinit var restore: RestoreService

    @Inject
    lateinit var backups: BackupService

    @Inject
    lateinit var scheduler: BackupScheduler

    @Inject
    lateinit var store: BackupStore

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        hilt.inject()
    }

    @Test
    fun `the graph provides the backup services and builds the worker`() = runTest {
        assertThat(store.dir).isEqualTo(File(context.filesDir, BackupStore.DIR_NAME))
        assertThat(export.exportFileName()).matches("tasker-export-\\d{4}-\\d{2}-\\d{2}\\.zip")
        assertThat(restore.findRestorable()).isNull()
        assertThat(backups.backUp()).isEqualTo(BackupResult.SkippedEmpty)

        val worker = TestListenableWorkerBuilder<BackupWorker>(context).setWorkerFactory(workerFactory).build()

        assertThat(worker.doWork()).isEqualTo(androidx.work.ListenableWorker.Result.success())
    }
}
