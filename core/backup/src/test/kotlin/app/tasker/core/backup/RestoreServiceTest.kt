package app.tasker.core.backup

import app.tasker.core.data.export.ExportTables
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RestoreServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val envs = ArrayList<BackupEnv>()

    private fun env(): BackupEnv = BackupEnv(temp.newFolder()).also { envs += it }

    @After
    fun tearDown() = envs.forEach { it.close() }

    /** The old phone: data and a backup of logical day 2026-10-16. */
    private suspend fun oldPhone(): BackupEnv = env().apply {
        data.fill()
        backups.backUp()
    }

    /** A new phone after Auto Backup restored `backups/latest.json.gz` only. */
    private fun newPhone(from: BackupEnv): BackupEnv = env().apply {
        store.dir.mkdirs()
        from.store.latest.copyTo(store.latest)
    }

    @Test
    fun `nothing is offered without backups`() = runTest {
        assertThat(env().restore.findRestorable()).isNull()
    }

    @Test
    fun `nothing is offered when the database has data`() = runTest {
        val old = oldPhone()

        assertThat(old.restore.findRestorable()).isNull()
    }

    @Test
    fun `a backup restored by auto backup is offered with its day and size and restored in one go`() = runTest {
        val old = oldPhone()
        val phone = newPhone(old)

        val candidate = checkNotNull(phone.restore.findRestorable())

        assertThat(candidate.file).isEqualTo(phone.store.latest)
        assertThat(candidate.day).isEqualTo(LocalDate.parse("2026-10-16"))
        assertThat(candidate.taskCount).isEqualTo(4)
        assertThat(candidate.summary.projects).isEqualTo(1)
        assertThat(phone.data.exporter.isEmpty()).isTrue() // nothing imported yet

        val result = phone.restore.restore(candidate)

        assertThat(result).isEqualTo(ImportResult.Imported(candidate.summary))
        assertThat(phone.data.exporter.export().tables).isEqualTo(old.data.exporter.export().tables)
        assertThat(phone.data.settings.current()).isEqualTo(old.data.settings.current())
        assertThat(phone.restore.findRestorable()).isNull()
    }

    @Test
    fun `the newest readable backup is offered when the latest one is damaged`() = runTest {
        val old = oldPhone()
        val phone = env()
        phone.store.dir.mkdirs()
        val dated = File(phone.store.dir, BackupStore.fileName(LocalDate.parse("2026-10-16")))
        old.store.latest.copyTo(dated)
        phone.store.latest.writeBytes(ExportFiles.gzip("{\"format\":\"tasker-export\""))
        phone.store.latest.setLastModified(dated.lastModified() + 1_000)

        val candidate = checkNotNull(phone.restore.findRestorable())

        assertThat(candidate.file).isEqualTo(dated)
        assertThat(phone.restore.restore(candidate)).isInstanceOf(ImportResult.Imported::class.java)
    }

    @Test
    fun `the most recently written backup wins`() = runTest {
        val old = oldPhone()
        val phone = env()
        phone.store.dir.mkdirs()
        val older = File(phone.store.dir, BackupStore.fileName(LocalDate.parse("2026-10-10")))
        val newer = File(phone.store.dir, BackupStore.fileName(LocalDate.parse("2026-10-16")))
        old.store.latest.copyTo(older)
        old.store.latest.copyTo(newer)
        older.setLastModified(newer.lastModified() - 86_400_000)

        assertThat(checkNotNull(phone.restore.findRestorable()).file).isEqualTo(newer)
    }

    @Test
    fun `a backup without tasks and projects is not offered`() = runTest {
        val phone = env()
        val empty = phone.data.exporter.export().copy(tables = ExportTables())
        phone.store.write(LocalDate.parse("2026-10-05"), ExportFiles.gzip(phone.data.exporter.encode(empty)))

        assertThat(phone.restore.findRestorable()).isNull()
    }

    @Test
    fun `restore refuses data that appeared after the offer unless asked to replace it`() = runTest {
        val old = oldPhone()
        val phone = newPhone(old)
        val candidate = checkNotNull(phone.restore.findRestorable())
        phone.data.add("успел создать задачу")

        val refused = phone.restore.restore(candidate)

        assertThat(refused).isEqualTo(ImportResult.NotEmpty(candidate.summary))
        assertThat(phone.data.exporter.export().tables.task).hasSize(1)
        assertThat(phone.restore.restore(candidate, replace = true)).isEqualTo(ImportResult.Imported(candidate.summary))
        assertThat(phone.data.exporter.export().tables).isEqualTo(old.data.exporter.export().tables)
    }

    @Test
    fun `a backup that disappeared is reported as unreadable`() = runTest {
        val old = oldPhone()
        val phone = newPhone(old)
        val candidate = checkNotNull(phone.restore.findRestorable())
        phone.store.latest.delete()

        assertThat(phone.restore.restore(candidate)).isEqualTo(ImportResult.Invalid(ImportProblem.UNREADABLE))
    }
}
