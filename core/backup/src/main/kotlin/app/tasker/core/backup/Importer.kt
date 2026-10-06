package app.tasker.core.backup

import app.tasker.core.data.export.DataExporter
import app.tasker.core.data.export.ExportBundle
import app.tasker.core.data.export.ExportFormat
import app.tasker.core.domain.time.DayClock
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Decoding and the guarded import shared by [ExportService] and [RestoreService].
 *
 * Import never loses data silently: it refuses a non-empty database unless asked to replace it, and before replacing
 * it saves the current data as today's backup. [DataExporter.import] clears the tables before it fills them, so when
 * filling fails (rows this version cannot store) the previous data is put back.
 */
@Singleton
internal class Importer @Inject constructor(
    private val exporter: DataExporter,
    private val backups: BackupService,
    private val clock: DayClock,
    private val lock: DataLock,
) {
    /**
     * Decodes export.json text and checks that it can be summarized.
     *
     * @throws ImportFileException with the reason the text is not importable.
     */
    fun decode(text: String): ExportBundle {
        val bundle = try {
            exporter.decode(text)
        } catch (e: IllegalArgumentException) {
            throw ImportFileException(classify(text), "Not an importable export: ${e.message}", e)
        }
        return bundle.also { summary(it) }
    }

    /** @throws ImportFileException when the export time is unreadable, i.e. the file was not written by the app. */
    fun summary(bundle: ExportBundle): ExportSummary {
        val exportedAt = try {
            Instant.parse(bundle.exportedAt)
        } catch (e: DateTimeException) {
            throw ImportFileException(ImportProblem.DAMAGED, "Invalid export time ${bundle.exportedAt}", e)
        }
        return ExportSummary(exportedAt, clock.logicalDay(exportedAt), bundle.tables.task.size, bundle.tables.project.size)
    }

    /** Replaces the data with [bundle]; see [ExportService.importFrom] for the rules. Call on an I/O dispatcher. */
    suspend fun import(bundle: ExportBundle, replace: Boolean): ImportResult = lock.withLock {
        val summary = summary(bundle)
        val previous = if (exporter.isEmpty()) {
            null
        } else {
            if (!replace) return@withLock ImportResult.NotEmpty(summary)
            exporter.export().also { backups.writeSafetyCopy(it) }
        }
        val problem = importOrPutBack(bundle, previous)
        if (problem == null) ImportResult.Imported(summary) else ImportResult.Invalid(problem)
    }

    /** Imports [bundle]; on failure puts [previous] back and returns the problem. */
    private suspend fun importOrPutBack(bundle: ExportBundle, previous: ExportBundle?): ImportProblem? {
        try {
            exporter.import(bundle)
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            putBack(previous, e)
            throw e
        } catch (e: Exception) {
            // Rows this version cannot store: unknown enum names, broken references, duplicate ids.
            putBack(previous, e)
            return ImportProblem.DAMAGED
        }
    }

    private suspend fun putBack(previous: ExportBundle?, failure: Exception) {
        if (previous == null) return
        try {
            exporter.import(previous)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The safety copy is still on disk: with the database empty, RestoreService offers it on the next start.
            failure.addSuppressed(e)
            throw failure
        }
    }

    /** Tells a foreign file from an export of a newer app version; called only after decoding failed. */
    private fun classify(text: String): ImportProblem {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return ImportProblem.NOT_AN_EXPORT
        if ((root[FORMAT_KEY] as? JsonPrimitive)?.contentOrNull != ExportFormat.NAME) return ImportProblem.NOT_AN_EXPORT
        val version = (root[VERSION_KEY] as? JsonPrimitive)?.intOrNull
        return if (version != null && version > ExportFormat.VERSION) ImportProblem.NEWER_VERSION else ImportProblem.DAMAGED
    }

    private companion object {
        const val FORMAT_KEY = "format"
        const val VERSION_KEY = "format_version"
    }
}
