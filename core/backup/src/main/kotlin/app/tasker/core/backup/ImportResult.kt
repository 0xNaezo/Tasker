package app.tasker.core.backup

import java.time.Instant
import java.time.LocalDate

/** What an export file or a backup holds; shown before import or restore ("Restore data from <day>?"). */
data class ExportSummary(
    val exportedAt: Instant,
    /** Logical day of [exportedAt] (tech plan §7.8): the day whose data the file holds. */
    val day: LocalDate,
    val tasks: Int,
    val projects: Int,
)

/** Outcome of an import or a restore. Only [Imported] changed anything. */
sealed interface ImportResult {
    /** The data of the file replaced the database and the settings. */
    data class Imported(val summary: ExportSummary) : ImportResult

    /** The database has data and `replace` was false: ask the user, then call again with `replace = true`. */
    data class NotEmpty(val summary: ExportSummary) : ImportResult

    /** The file cannot be imported; the current data is untouched. */
    data class Invalid(val problem: ImportProblem) : ImportResult
}

/** Why a file cannot be imported or restored. */
enum class ImportProblem {
    /** The file could not be opened or read (permission, missing file, I/O error). */
    UNREADABLE,

    /** Not a Tasker export: another archive, another JSON, a random file. */
    NOT_AN_EXPORT,

    /** A Tasker export made by a newer version of the app; update the app to read it. */
    NEWER_VERSION,

    /** A Tasker export whose content is broken (truncated archive, invalid rows). */
    DAMAGED,

    /** Unpacks to more than 256 MB; not something this app wrote. */
    TOO_LARGE,
}
