package app.tasker.feature.search

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * [text] with the match [ranges] in [style]. The ranges come from the search stemmer (§15) and refer to [text]; parts
 * outside it are ignored rather than trusted.
 */
internal fun highlighted(text: String, ranges: List<IntRange>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    ranges.forEach { range ->
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        if (end > start) addStyle(style, start, end)
    }
}

/** A one-line excerpt of a note and the match ranges inside it. */
@Immutable
internal data class Snippet(val text: String, val highlights: List<IntRange>)

/** Excerpts of long notes around the first match, so that a result shows why it was found (SRC-1). */
internal object Snippets {
    const val MAX_LENGTH = 140
    const val LEAD = 40
    private const val ELLIPSIS = "…"

    /**
     * Cuts [text] to at most [maxLength] characters (plus ellipses) starting up to [lead] characters before the first
     * match, on word boundaries where possible. Line breaks become spaces; [ranges] are moved into the excerpt and
     * clipped to it.
     */
    fun around(text: String, ranges: List<IntRange>, maxLength: Int = MAX_LENGTH, lead: Int = LEAD): Snippet {
        val flat = buildString(text.length) { text.forEach { append(if (it.isWhitespace()) ' ' else it) } }
        val valid = ranges.filter { !it.isEmpty() && it.first in flat.indices }.sortedBy { it.first }
        if (flat.length <= maxLength) return Snippet(flat, valid.map { it.first..minOf(it.last, flat.lastIndex) })
        val first = valid.firstOrNull()
        var start = ((first?.first ?: 0) - lead).coerceAtLeast(0)
        if (start > 0 && first != null) {
            val space = flat.indexOf(' ', start)
            if (space in start until first.first) start = space + 1
        }
        var end = (start + maxLength).coerceAtMost(flat.length)
        if (end < flat.length) {
            val space = flat.lastIndexOf(' ', end)
            if (space > (first?.last ?: start)) end = space
        }
        val prefix = if (start > 0) ELLIPSIS else ""
        val suffix = if (end < flat.length) ELLIPSIS else ""
        val shift = prefix.length - start
        val highlights = valid.mapNotNull { range ->
            val from = maxOf(range.first, start)
            val to = minOf(range.last, end - 1)
            if (from > to) null else (from + shift)..(to + shift)
        }
        return Snippet(prefix + flat.substring(start, end) + suffix, highlights)
    }
}
