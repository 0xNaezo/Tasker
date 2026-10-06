package app.tasker.core.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupWorkerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val envs = ArrayList<BackupEnv>()

    @After
    fun tearDown() = envs.forEach { it.close() }

    private fun env(root: File = temp.newFolder(), folder: BackupFolder? = null): BackupEnv =
        (if (folder == null) BackupEnv(root) else BackupEnv(root, folder = folder)).also { envs += it }

    private fun worker(env: BackupEnv, attempt: Int = 0): BackupWorker = TestListenableWorkerBuilder<BackupWorker>(context)
        .setRunAttemptCount(attempt)
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    BackupWorker(appContext, workerParameters, env.backups)
            },
        )
        .build()

    @Test
    fun `the worker writes the daily backup`() = runTest {
        val env = env()
        env.data.add("задача")

        val result = worker(env).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(env.store.dated().map { it.day.toString() }).containsExactly("2026-10-06")
    }

    @Test
    fun `the worker succeeds on an empty database without writing anything`() = runTest {
        val env = env()

        assertThat(worker(env).doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(env.store.dir.exists()).isFalse()
    }

    @Test
    fun `a failing backup folder does not fail the worker`() = runTest {
        val env = env(folder = { _, _, _ -> throw IllegalArgumentException("Unknown document") })
        env.data.add("задача")
        env.data.settings.update { it.copy(backupTreeUri = "content://gone/tree/x") }

        assertThat(worker(env).doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(env.store.latest.exists()).isTrue()
    }

    @Test
    fun `a failed local write is retried a few times`() = runTest {
        val root = temp.newFolder()
        File(root, BackupStore.DIR_NAME).writeText("a file where the folder should be")
        val env = env(root)
        env.data.add("задача")

        assertThat(worker(env, attempt = 0).doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(worker(env, attempt = 2).doWork()).isEqualTo(ListenableWorker.Result.failure())
    }
}
