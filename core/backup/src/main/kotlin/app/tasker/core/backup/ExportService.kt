package app.tasker.core.backup

import android.content.Context
import android.net.Uri
import app.tasker.core.data.di.IoDispatcher
import app.tasker.core.data.export.DataExporter
import app.tasker.core.domain.time.DayClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Export and import through files the user picks (DATA-1, tech plan §16).
 *
 * Export: the UI launches `ActivityResultContracts.CreateDocument(ExportService.MIME_TYPE)` with [exportFileName] and
 * passes the returned Uri to [exportTo]. The ZIP holds `export.json` (complete, versioned) and `tasks.md` (readable).
 *
 * Import: the UI launches `ActivityResultContracts.OpenDocument` (e.g. with `arrayOf("application/zip",
 * "application/json", "application/gzip", "application/octet-stream")`) and passes the Uri to [importFrom]. An export
 * ZIP, a bare `export.json` and a backup `*.json.gz` from the backup folder are all accepted.
 */
@Singleton
class ExportService @Inject internal constructor(
    @param:ApplicationContext private val context: Context,
    private val exporter: DataExporter,
    private val importer: Importer,
    private val clock: DayClock,
    private val lock: DataLock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Suggested name of today's export file, e.g. `tasker-export-2026-10-06.zip`. */
    fun exportFileName(): String = exportFileName(clock.today())

    /** Writes the export ZIP to [uri]; returns what was exported. */
    suspend fun exportTo(uri: Uri): ExportSummary = withContext(io) {
        val bundle = lock.withLock { exporter.export() }
        val summary = importer.summary(bundle)
        val json = exporter.encode(bundle)
        val markdown = MarkdownExporter.render(TasksDocument.from(bundle, clock.zone()))
        context.contentResolver.openTruncating(uri).use { ExportFiles.writeZip(it, json, markdown, summary.exportedAt) }
        summary
    }

    /**
     * Imports an export or a backup from [uri]. An empty database is filled right away. A database with data is left
     * untouched and [ImportResult.NotEmpty] is returned, unless [replace] is true: then the current data is saved as
     * today's backup and replaced. Settings are replaced along with the data.
     */
    suspend fun importFrom(uri: Uri, replace: Boolean = false): ImportResult = withContext(io) {
        val bundle = try {
            importer.decode(openInput(uri).use { ExportFiles.readExportJson(it) })
        } catch (e: ImportFileException) {
            return@withContext ImportResult.Invalid(e.problem)
        }
        importer.import(bundle, replace)
    }

    private fun openInput(uri: Uri): InputStream = try {
        context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Cannot read $uri")
    } catch (e: IOException) {
        throw ImportFileException(ImportProblem.UNREADABLE, "Cannot read $uri", e)
    } catch (e: SecurityException) {
        throw ImportFileException(ImportProblem.UNREADABLE, "No permission to read $uri", e)
    }

    companion object {
        /** MIME type for `ActivityResultContracts.CreateDocument`. */
        const val MIME_TYPE = "application/zip"

        /** Name of the export file of [date]: `tasker-export-2026-10-06.zip`. */
        fun exportFileName(date: LocalDate): String = "tasker-export-$date.zip"
    }
}
