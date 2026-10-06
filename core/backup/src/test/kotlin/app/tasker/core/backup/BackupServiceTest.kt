package app.tasker.core.backup

import androidx.documentfile.provider.DocumentFile
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
class BackupServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val envs = ArrayList<BackupEnv>()

    private fun env(folder: BackupFolder? = null): BackupEnv {
        val root = temp.newFolder()
        return (if (folder == null) BackupEnv(root) else BackupEnv(root, folder = folder)).also { envs += it }
    }

    @After
    fun tearDown() = envs.forEach { it.close() }

    @Test
    fun `a backup is gzipped export json of the logical day and becomes latest`() = runTest {
        val env = env()
        env.data.fill()

        val result = env.backups.backUp() as BackupResult.Written

        val day = LocalDate.parse("2026-10-16")
        assertThat(result.day).isEqualTo(day)
        assertThat(result.file).isEqualTo(File(env.store.dir, "backup-2026-10-16.json.gz"))
        assertThat(result.removed).isEmpty()
        assertThat(result.folder).isEqualTo(FolderCopy.NotSet)
        assertThat(env.store.latest.readBytes()).isEqualTo(result.file.readBytes())
        val restored = env.data.exporter.decode(env.store.read(result.file))
        assertThat(restored.tables).isEqualTo(env.data.exporter.export().tables)
        assertThat(restored.settings).isEqualTo(env.data.settings.current())
        assertThat(env.store.dir.list()).asList().containsExactly("backup-2026-10-16.json.gz", "latest.json.gz")
    }

    @Test
    fun `a second run on the same day replaces that day's copy`() = runTest {
        val env = env()
        env.data.add("первая")
        env.backups.backUp()
        env.data.add("вторая")

        env.backups.backUp()

        assertThat(env.store.dated()).hasSize(1)
        assertThat(env.data.exporter.decode(env.store.read(env.store.latest)).tables.task).hasSize(2)
    }

    @Test
    fun `an empty database is not backed up and keeps the last good copy`() = runTest {
        val env = env()
        env.store.dir.mkdirs()
        env.store.latest.writeBytes(byteArrayOf(1, 2, 3))

        val result = env.backups.backUp()

        assertThat(result).isEqualTo(BackupResult.SkippedEmpty)
        assertThat(env.store.latest.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(env.store.dated()).isEmpty()
    }

    @Test
    fun `old copies are rotated to seven daily and four weekly`() = runTest {
        val env = env()
        env.data.add("задача")
        env.store.dir.mkdirs()
        val today = LocalDate.parse("2026-10-06")
        val old = generateSequence(today.minusDays(1)) { it.minusDays(1) }.take(60).toList()
        old.forEach { File(env.store.dir, BackupStore.fileName(it)).writeBytes(byteArrayOf(0)) }
        File(env.store.dir, "notes.txt").writeText("not a backup")
        File(env.store.dir, "backup-2026-09-01.json.gz.tmp").writeText("left by a killed process")

        val result = env.backups.backUp() as BackupResult.Written

        val kept = BackupRotation.keep(old + today, today)
        assertThat(kept).hasSize(BackupRotation.DAILY + BackupRotation.WEEKLY)
        assertThat(env.store.dated().map { it.day }).containsExactlyElementsIn(kept)
        assertThat(result.removed.map { BackupStore.dayOf(it.name) }).containsExactlyElementsIn(old - kept)
        assertThat(File(env.store.dir, "notes.txt").exists()).isTrue()
        assertThat(File(env.store.dir, "backup-2026-09-01.json.gz.tmp").exists()).isFalse()
        assertThat(env.store.latest.exists()).isTrue()
    }

    @Test
    fun `a failing backup folder does not fail the backup`() = runTest {
        val env = env(folder = { _, _, _ -> throw SecurityException("Permission revoked") })
        env.data.add("задача")
        env.data.settings.update { it.copy(backupTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ABackup") }

        val result = env.backups.backUp() as BackupResult.Written

        assertThat(result.folder).isInstanceOf(FolderCopy.Failed::class.java)
        assertThat((result.folder as FolderCopy.Failed).error).isInstanceOf(SecurityException::class.java)
        assertThat(result.file.exists()).isTrue()
    }

    @Test
    fun `an unreachable tree uri is reported, not thrown`() = runTest {
        val env = env()
        env.data.add("задача")
        env.data.settings.update { it.copy(backupTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ABackup") }

        val result = env.backups.backUp() as BackupResult.Written

        assertThat(result.folder).isInstanceOf(FolderCopy.Failed::class.java)
        assertThat(env.store.latest.exists()).isTrue()
    }

    @Test
    fun `the folder gets a dated copy and keeps only rotated copies of its own`() = runTest {
        val env = env()
        val dir = temp.newFolder("picked")
        val today = LocalDate.parse("2026-10-06")
        val old = generateSequence(today.minusDays(1)) { it.minusDays(1) }.take(30).toList()
        old.forEach { File(dir, DocumentBackupFolder.fileName(it)).writeBytes(byteArrayOf(0)) }
        val foreign = listOf("backup-2026-01-01.json.gz", "tasker-backup-2026-01-01 (1).json.gz", "photo.jpg")
        foreign.forEach { File(dir, it).writeText("someone else's") }
        val backup = temp.newFile("backup.json.gz").apply { writeBytes(ExportFiles.gzip("{}")) }

        DocumentBackupFolder(env.context).storeIn(DocumentFile.fromFile(dir), backup, today)

        val copy = File(dir, "tasker-backup-2026-10-06.json.gz")
        assertThat(copy.readBytes()).isEqualTo(backup.readBytes())
        val ownCopies = dir.list().orEmpty().mapNotNull(DocumentBackupFolder::dayOf)
        assertThat(ownCopies).containsExactlyElementsIn(BackupRotation.keep(old + today, today))
        foreign.forEach { assertThat(File(dir, it).exists()).isTrue() }
    }

    @Test
    fun `a copy restored by auto backup is kept before it is replaced`() = runTest {
        val previous = env()
        previous.data.fill() // logical day 2026-10-16
        previous.backups.backUp()
        val env = env()
        env.store.dir.mkdirs()
        previous.store.latest.copyTo(env.store.latest)
        env.data.add("начал заново") // the user declined the restore offer

        env.backups.backUp()

        assertThat(env.store.dated().map { it.day }).containsExactly(LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-05")).inOrder()
        val kept = env.store.dated().last().file
        assertThat(env.data.exporter.decode(env.store.read(kept)).tables).isEqualTo(previous.data.exporter.export().tables)
    }
}
