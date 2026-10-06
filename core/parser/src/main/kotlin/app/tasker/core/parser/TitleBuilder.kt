package app.tasker.core.parser

/**
 * The task text: tokens that are not part of a field, with the original spacing (tech plan §9.1, step 9).
 *
 * A preposition or separator left at an edge of the title is dropped when it is "left over" by an extraction: a preposition
 * whose neighbour is an extracted date, time or URL ("созвон завтра в" → "созвон"), a separator next to any extracted
 * fragment ("#client, срочно" → "срочно"). Brackets that only held an extracted fragment are dropped too.
 *
 * @param removed tokens that went into fields or links.
 * @param anchors removed tokens of dates, times and URLs: prepositions next to them dangle.
 */
internal class TitleBuilder(
    private val tokens: List<Token>,
    private val removed: BooleanArray,
    anchors: BooleanArray,
    private val danglingKeys: Set<String>,
) {
    private val anchors = anchors.copyOf()
    private val dropped = BooleanArray(tokens.size)

    fun build(): String {
        val kept = keptWithoutEmptyBrackets()
        var lo = 0
        var hi = kept.size - 1
        while (lo <= hi && droppedAtStart(kept[lo])) lo++
        while (hi >= lo && droppedAtEnd(kept[hi])) hi--
        val sb = StringBuilder()
        for (m in lo..hi) {
            val token = tokens[kept[m]]
            if (m > lo) sb.append(separator(tokens[kept[m - 1]], token))
            sb.append(token.text)
        }
        return sb.toString()
    }

    private fun keptWithoutEmptyBrackets(): List<Int> {
        val kept = tokens.indices.filterTo(ArrayList()) { !removed[it] }
        var m = 0
        while (m + 1 < kept.size) {
            val open = tokens[kept[m]]
            val close = tokens[kept[m + 1]]
            val emptied = close.index - open.index > 1 && !open.literal && !close.literal
            if (emptied && open.kind == TokenKind.PUNCT && BRACKETS[open.text] == close.text) {
                dropped[open.index] = true
                dropped[close.index] = true
                kept.removeAt(m + 1)
                kept.removeAt(m)
                m = (m - 1).coerceAtLeast(0)
            } else {
                m++
            }
        }
        return kept
    }

    private fun droppedAtStart(index: Int): Boolean {
        val token = tokens[index]
        val drop = when {
            token.literal -> false
            isEdgePunctuation(token) -> isGone(index - 1)
            isDangling(token) -> anchorTowards(index, 1)
            else -> false
        }
        if (drop) drop(index)
        return drop
    }

    private fun droppedAtEnd(index: Int): Boolean {
        val token = tokens[index]
        val drop = when {
            token.literal -> false
            isEdgePunctuation(token) -> isGone(index + 1)
            isDangling(token) -> anchorTowards(index, -1) || anchorTowards(index, 1)
            else -> false
        }
        if (drop) drop(index)
        return drop
    }

    private fun drop(index: Int) {
        dropped[index] = true
        anchors[index] = true
    }

    private fun isDangling(token: Token): Boolean = token.kind == TokenKind.WORD && token.key in danglingKeys

    private fun isEdgePunctuation(token: Token): Boolean = token.kind == TokenKind.PUNCT && token.text in EDGE_PUNCTUATION

    private fun isGone(index: Int): Boolean = index in tokens.indices && (removed[index] || dropped[index])

    /** The nearest token in [direction] that is kept or anchoring is an anchor; other extracted fields are skipped. */
    private fun anchorTowards(index: Int, direction: Int): Boolean {
        var k = index + direction
        while (k in tokens.indices) {
            if (anchors[k]) return true
            if (!removed[k] && !dropped[k]) return false
            k += direction
        }
        return false
    }

    private fun separator(previous: Token, current: Token): String {
        val gap = current.index - previous.index > 1
        return when {
            !gap -> if (current.spaceBefore) " " else ""
            !current.spaceBefore && current.kind == TokenKind.PUNCT && current.text in CLOSING -> ""
            previous.kind == TokenKind.PUNCT && previous.text in OPENING && !tokens[previous.index + 1].spaceBefore -> ""
            else -> " "
        }
    }

    private companion object {
        val EDGE_PUNCTUATION = setOf(",", ";", ":", "—", "–", "-", "/", "|", "·")
        val CLOSING = setOf(",", ".", ";", ":", "!", "?", ")", "]", "}", "»", "…", "”", "’")
        val OPENING = setOf("(", "[", "{", "«", "“", "‘")
        val BRACKETS = mapOf("(" to ")", "[" to "]", "{" to "}", "«" to "»", "“" to "”")
    }
}
