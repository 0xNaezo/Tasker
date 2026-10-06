package app.tasker.core.parser

import app.tasker.core.model.Bucket
import app.tasker.core.model.Estimate
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** How an ambiguous slash date such as "12/10" is read; chosen by the locale region (tech plan §9.3, rule 6). */
enum class SlashDateOrder { DAY_MONTH, MONTH_DAY }

/**
 * Everything the parser needs besides the text. The parser never reads the system clock (tech plan §7.8).
 *
 * @property now local wall-clock time; decides whether a deadline time without a date is still ahead today.
 * @property today logical today; the caller applies the 04:00 day boundary.
 * @property slashDateOrder how to read "12/10".
 * @property literalRanges ranges of [ParseResult.input] the user returned to plain text by removing a chip: they stay in the
 *   title and are never parsed. A range that touches a link takes the whole link (a literal range never splits a URL).
 * @property knownProjects names of existing projects, compared case-insensitively ("ё" equals "е").
 */
data class ParseContext(
    val now: LocalDateTime,
    val today: LocalDate,
    val slashDateOrder: SlashDateOrder = SlashDateOrder.DAY_MONTH,
    val literalRanges: List<IntRange> = emptyList(),
    val knownProjects: Set<String> = emptySet(),
)

/**
 * Result of [InputParser.parse].
 *
 * @property input the string exactly as given; all ranges refer to it.
 * @property title the task text: the input without the recognized fragments and links. Never blank for a non-blank input:
 *   when nothing else remains, it is the shortened first URL (interpretation 20) or the whole input (interpretation 19).
 * @property fields recognized fields ordered by the start of their first range; ranges of different fields never overlap.
 * @property links URLs (removed from the title) and e-mails (kept in the title) in input order (EXC-5).
 */
data class ParseResult(
    val input: String,
    val title: String,
    val fields: List<ParsedField>,
    val links: List<ParsedLink>,
) {
    /** The input starts with an "already done" prefix (CAP-8). */
    val alreadyDone: Boolean get() = fields.any { it.value == ParsedValue.AlreadyDone }

    val deadline: ParsedValue.Deadline? get() = first<ParsedValue.Deadline>()

    val planDate: LocalDate? get() = first<ParsedValue.PlanDate>()?.date

    val estimate: Estimate? get() = first<ParsedValue.Size>()?.estimate

    val project: ParsedValue.Project? get() = first<ParsedValue.Project>()

    /** Tag names in input order without case-insensitive duplicates. */
    val tags: List<String>
        get() = fields.mapNotNull { (it.value as? ParsedValue.Tag)?.name }.distinctBy { it.lowercase() }

    val bucket: Bucket? get() = first<ParsedValue.Horizon>()?.bucket

    private inline fun <reified T : ParsedValue> first(): T? = fields.firstNotNullOfOrNull { it.value as? T }
}

/** Value of one recognized field. */
sealed interface ParsedValue {
    /** Hard date (CAP-3). [time] is set only when the input names a time together with a deadline marker. */
    data class Deadline(val date: LocalDate, val time: LocalTime?) : ParsedValue

    /** Soft "going to do it" date: a date without a deadline marker. */
    data class PlanDate(val date: LocalDate) : ParsedValue

    /** Rough size from a standalone `S`, `M`, `L` or Cyrillic `М`. */
    data class Size(val estimate: Estimate) : ParsedValue

    /** `#name`. For a known project [name] is spelled as in [ParseContext.knownProjects]; otherwise [isNew] is true. */
    data class Project(val name: String, val isNew: Boolean) : ParsedValue

    /** `@name`. */
    data class Tag(val name: String) : ParsedValue

    /** Horizon word: "сегодня", "на неделе", "когда-нибудь" and their uk/en counterparts. */
    data class Horizon(val bucket: Bucket) : ParsedValue

    /** CAP-8 prefix such as "уже сделал": the task is created done and off-plan. */
    data object AlreadyDone : ParsedValue
}

/**
 * One recognized field, shown as a chip (CAP-4).
 *
 * @property ranges positions in the ORIGINAL input; a deadline may consist of several fragments ("пт ... до 18:00").
 * @property language language pack that recognized the field ("ru", "uk", "en") or `null` for language-neutral syntax
 *   (`#project`, `@tag`, estimate, numeric dates and times).
 */
data class ParsedField(val value: ParsedValue, val ranges: List<IntRange>, val language: String?) {
    /** The fragments of [input] covered by this field, joined with a space. */
    fun text(input: String): String = ranges.joinToString(" ") { input.substring(it) }
}

enum class LinkKind { URL, EMAIL }

/**
 * A link found in the input (EXC-5).
 *
 * @property url normalized target: "www.x.com" becomes "https://www.x.com", an e-mail becomes "mailto:...".
 * @property range position in the original input.
 */
data class ParsedLink(val url: String, val range: IntRange, val kind: LinkKind)
