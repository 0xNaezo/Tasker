package app.tasker.core.backup

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A file that cannot be imported, with the reason the UI shows. */
internal class ImportFileException(val problem: ImportProblem, message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * File formats of export and backup (tech plan §16), independent of Android so they are tested on the JVM:
 * - the export ZIP with `export.json` and `tasks.md`;
 * - a backup, `export.json` compressed with gzip;
 * - a bare `export.json`.
 *
 * Reading detects the format by its first bytes, so import accepts any of the three whatever the file is called.
 */
internal object ExportFiles {
    const val JSON_ENTRY = "export.json"
    const val MARKDOWN_ENTRY = "tasks.md"

    /** Upper bound of an unpacked export.json; real exports are a few megabytes even after years of use. */
    const val MAX_EXPORT_BYTES: Long = 256L * 1024 * 1024

    /** Writes the export ZIP to [out] and closes it. Entries carry [time], so the same data gives the same bytes. */
    fun writeZip(out: OutputStream, json: String, markdown: String, time: Instant) {
        ZipOutputStream(out).use { zip ->
            zip.putEntry(JSON_ENTRY, json, time)
            zip.putEntry(MARKDOWN_ENTRY, markdown, time)
        }
    }

    fun gzip(text: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(text.encodeToByteArray()) }
        return bytes.toByteArray()
    }

    /**
     * Reads export.json from [input], which holds an export ZIP, a gzip backup or plain JSON, and closes it.
     *
     * @throws ImportFileException when the content is not readable as one of these formats or is too large.
     */
    fun readExportJson(input: InputStream, limit: Long = MAX_EXPORT_BYTES): String {
        val buffered = BufferedInputStream(input)
        val text = try {
            val head = peek(buffered, MAGIC_LENGTH)
            when {
                head.startsWith(ZIP_MAGIC) -> ZipInputStream(buffered).use { readZipEntry(it, limit) }
                head.startsWith(GZIP_MAGIC) -> GZIPInputStream(buffered).use { readBounded(it, limit) }
                else -> buffered.use { readBounded(it, limit) }
            }
        } catch (e: ImportFileException) {
            throw e
        } catch (e: IOException) {
            throw ImportFileException(ImportProblem.DAMAGED, "The file is damaged: ${e.message}", e)
        }
        return text.removePrefix(BYTE_ORDER_MARK)
    }

    private fun readZipEntry(zip: ZipInputStream, limit: Long): String {
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name.substringAfterLast('/') == JSON_ENTRY) return readBounded(zip, limit)
            entry = zip.nextEntry
        }
        throw ImportFileException(ImportProblem.NOT_AN_EXPORT, "The archive has no $JSON_ENTRY")
    }

    private fun readBounded(input: InputStream, limit: Long): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw ImportFileException(ImportProblem.TOO_LARGE, "The file unpacks to more than $limit bytes")
            out.write(buffer, 0, read)
        }
        return out.toByteArray().decodeToString()
    }

    private fun peek(input: BufferedInputStream, count: Int): ByteArray {
        input.mark(count)
        val head = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = input.read(head, filled, count - filled)
            if (read < 0) break
            filled += read
        }
        input.reset()
        return head.copyOf(filled)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ZipOutputStream.putEntry(name: String, text: String, time: Instant) {
        val entry = ZipEntry(name)
        entry.time = time.toEpochMilli()
        putNextEntry(entry)
        write(text.encodeToByteArray())
        closeEntry()
    }

    private const val MAGIC_LENGTH = 4
    private const val BUFFER_SIZE = 64 * 1024
    private const val BYTE_ORDER_MARK = "\uFEFF"
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val GZIP_MAGIC = byteArrayOf(0x1F, 0x8B.toByte())
}
