package app.tasker.core.backup

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class ExportFilesTest {
    private val json = """{"format":"tasker-export","note":"Ünïcödé · задача"}"""
    private val markdown = "# Tasker export\n"
    private val time = Instant.parse("2026-10-06T07:00:00Z")

    private fun zip(): ByteArray = ByteArrayOutputStream().also { ExportFiles.writeZip(it, json, markdown, time) }.toByteArray()

    private fun read(bytes: ByteArray, limit: Long = ExportFiles.MAX_EXPORT_BYTES) =
        ExportFiles.readExportJson(ByteArrayInputStream(bytes), limit)

    @Test
    fun `the export zip holds export json and tasks md`() {
        val entries = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(zip())).use { input ->
            generateSequence { input.nextEntry }.forEach { entry ->
                assertThat(entry.time).isEqualTo(time.toEpochMilli())
                entries[entry.name] = input.readBytes().decodeToString()
            }
        }

        assertThat(entries).containsExactly("export.json", json, "tasks.md", markdown).inOrder()
    }

    @Test
    fun `the same data gives the same zip`() {
        assertThat(zip()).isEqualTo(zip())
    }

    @Test
    fun `export json is read from a zip, a gzip backup and a bare file`() {
        assertThat(read(zip())).isEqualTo(json)
        assertThat(read(ExportFiles.gzip(json))).isEqualTo(json)
        assertThat(read(json.encodeToByteArray())).isEqualTo(json)
        assertThat(read(("\uFEFF" + json).encodeToByteArray())).isEqualTo(json)
    }

    @Test
    fun `export json is found in a folder inside the zip`() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("tasker-export-2026-10-06/"))
                zip.putNextEntry(ZipEntry("tasker-export-2026-10-06/export.json"))
                zip.write(json.encodeToByteArray())
            }
        }.toByteArray()

        assertThat(read(bytes)).isEqualTo(json)
    }

    @Test
    fun `a zip without export json is not an export`() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("photo.jpg"))
                zip.write(byteArrayOf(1, 2, 3))
            }
        }.toByteArray()

        val error = assertThrows(ImportFileException::class.java) { read(bytes) }

        assertThat(error.problem).isEqualTo(ImportProblem.NOT_AN_EXPORT)
    }

    @Test
    fun `a truncated backup is damaged`() {
        val gz = ExportFiles.gzip(json.repeat(100))

        val error = assertThrows(ImportFileException::class.java) { read(gz.copyOf(gz.size / 2)) }

        assertThat(error.problem).isEqualTo(ImportProblem.DAMAGED)
    }

    @Test
    fun `content above the limit is refused before it fills the memory`() {
        val error = assertThrows(ImportFileException::class.java) { read(ExportFiles.gzip("x".repeat(10_000)), limit = 1_000) }

        assertThat(error.problem).isEqualTo(ImportProblem.TOO_LARGE)
    }
}
