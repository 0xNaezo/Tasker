package app.tasker.core.backup

import android.net.Uri
import app.tasker.core.data.export.ExportFormat
import app.tasker.core.model.TaskStatus
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipFile
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExportServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val source by lazy { BackupEnv(temp.newFolder("source")) }
    private val target by lazy { BackupEnv(temp.newFolder("target")) }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private fun file(name: String): File = File(temp.root, name)

    private fun uri(file: File): Uri = Uri.fromFile(file)

    @Test
    fun `an export zip imported into an empty database gives the same data`() = runTest {
        source.data.fill()
        val zip = file("export.zip")

        val exported = source.export.exportTo(uri(zip))
        val result = target.export.importFrom(uri(zip))

        val before = source.data.exporter.export()
        val after = target.data.exporter.export()
        assertThat(result).isEqualTo(ImportResult.Imported(exported))
        assertThat(after.tables).isEqualTo(before.tables)
        assertThat(after.settings).isEqualTo(before.settings)
        assertThat(exported.tasks).isEqualTo(4)
        assertThat(exported.projects).isEqualTo(1)
        assertThat(exported.day).isEqualTo(LocalDate.parse("2026-10-16"))
        assertThat(target.data.search.searchOnce("введение")).hasSize(1)
    }

    @Test
    fun `the zip holds export json and a readable tasks md`() = runTest {
        source.data.fill()
        val zip = file("export.zip")

        source.export.exportTo(uri(zip))

        ZipFile(zip).use { archive ->
            assertThat(archive.entries().toList().map { it.name }).containsExactly("export.json", "tasks.md").inOrder()
            val json = archive.getInputStream(archive.getEntry("export.json")).readBytes().decodeToString()
            val markdown = archive.getInputStream(archive.getEntry("tasks.md")).readBytes().decodeToString()
            assertThat(source.data.exporter.decode(json).tables).isEqualTo(source.data.exporter.export().tables)
            assertThat(markdown).startsWith("# Tasker export\n")
            val tasks = source.data.exporter.export().tables.task
            tasks.forEach { task -> assertThat(markdown.lines().count { it.startsWith("- ") && it.contains(task.title) }).isEqualTo(1) }
            val archived = tasks.single { it.status == TaskStatus.ARCHIVED.name }
            assertThat(markdown.substringAfter("## Archive")).contains(archived.title)
            assertThat(markdown).contains("Link: https://example.com/a")
            assertThat(markdown).contains("deadline 2026-10-09 18:00")
            assertThat(markdown).contains("@работа")
            assertThat(markdown).contains("### client")
            assertThat(markdown).contains("Context 2026-10-06: остановился на введении")
        }
    }

    @Test
    fun `import refuses a database with data unless asked to replace it`() = runTest {
        source.data.fill()
        val zip = file("export.zip")
        source.export.exportTo(uri(zip))
        target.data.add("моя задача")
        val own = target.data.exporter.export()

        val refused = target.export.importFrom(uri(zip))

        assertThat(refused).isInstanceOf(ImportResult.NotEmpty::class.java)
        assertThat((refused as ImportResult.NotEmpty).summary.tasks).isEqualTo(4)
        assertThat(target.data.exporter.export().tables).isEqualTo(own.tables)
        assertThat(target.store.dated()).isEmpty()

        val replaced = target.export.importFrom(uri(zip), replace = true)

        assertThat(replaced).isInstanceOf(ImportResult.Imported::class.java)
        assertThat(target.data.exporter.export().tables).isEqualTo(source.data.exporter.export().tables)
        // The replaced data was saved as today's backup first.
        val safety = target.store.dated().single()
        assertThat(target.data.exporter.decode(target.store.read(safety.file)).tables).isEqualTo(own.tables)
    }

    @Test
    fun `a bare export json and a gzip backup are imported too`() = runTest {
        source.data.fill()
        val json = source.data.exporter.encode(source.data.exporter.export())
        val bare = file("export.json").apply { writeText(json) }
        val gz = file("tasker-backup-2026-10-16.json.gz").apply { writeBytes(ExportFiles.gzip(json)) }

        assertThat(target.export.importFrom(uri(bare))).isInstanceOf(ImportResult.Imported::class.java)
        assertThat(target.data.exporter.export().tables).isEqualTo(source.data.exporter.export().tables)
        assertThat(target.export.importFrom(uri(gz), replace = true)).isInstanceOf(ImportResult.Imported::class.java)
        assertThat(target.data.exporter.export().tables).isEqualTo(source.data.exporter.export().tables)
    }

    @Test
    fun `files that are not exports are refused with a reason`() = runTest {
        val newer = source.data.exporter.encode(source.data.exporter.export().copy(formatVersion = ExportFormat.VERSION + 1))
        val cases = mapOf(
            file("notes.txt").apply { writeText("just some notes") } to ImportProblem.NOT_AN_EXPORT,
            file("other.json").apply { writeText("""{"format":"other","tasks":[]}""") } to ImportProblem.NOT_AN_EXPORT,
            file("newer.json").apply { writeText(newer) } to ImportProblem.NEWER_VERSION,
            file("broken.json").apply {
                writeText("""{"format":"tasker-export","format_version":1,"tables":5}""")
            } to ImportProblem.DAMAGED,
            file("missing.zip") to ImportProblem.UNREADABLE,
        )

        for ((input, problem) in cases) {
            assertThat(target.export.importFrom(uri(input))).isEqualTo(ImportResult.Invalid(problem))
        }
        assertThat(target.data.exporter.isEmpty()).isTrue()
    }

    @Test
    fun `a failed import puts the previous data back`() = runTest {
        source.data.fill()
        val bundle = source.data.exporter.export()
        // Decodes fine but cannot be stored: a status this version does not know.
        val broken = bundle.copy(tables = bundle.tables.copy(task = bundle.tables.task.map { it.copy(status = "SNOOZED") }))
        val input = file("broken.json").apply { writeText(source.data.exporter.encode(broken)) }
        target.data.add("моя задача")
        val own = target.data.exporter.export()

        val result = target.export.importFrom(uri(input), replace = true)

        assertThat(result).isEqualTo(ImportResult.Invalid(ImportProblem.DAMAGED))
        assertThat(target.data.exporter.export().tables).isEqualTo(own.tables)
    }

    @Test
    fun `the export file is named after the logical day`() {
        assertThat(ExportService.exportFileName(LocalDate.parse("2026-10-06"))).isEqualTo("tasker-export-2026-10-06.zip")
        assertThat(source.export.exportFileName()).isEqualTo("tasker-export-2026-10-06.zip")
        source.data.time.set(java.time.LocalDateTime.parse("2026-10-07T02:30")) // before the 04:00 day boundary
        assertThat(source.export.exportFileName()).isEqualTo("tasker-export-2026-10-06.zip")
    }
}
