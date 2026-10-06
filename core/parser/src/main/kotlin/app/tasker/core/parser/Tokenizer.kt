package app.tasker.core.parser

internal enum class TokenKind { WORD, NUMBER, PUNCT, PROJECT, TAG, LINK }

/**
 * A token of the normalized text, `[start, end)`.
 *
 * @property key comparison form: lowercase, "ё" → "е", without apostrophes and stress marks (words only).
 * @property spaceBefore the token is separated from the previous one by whitespace.
 * @property breakBefore that whitespace contained a line break, which also ends a phrase.
 * @property literal the token overlaps a literal range: it stays text and is never parsed.
 */
internal class Token(
    val index: Int,
    val kind: TokenKind,
    val start: Int,
    val end: Int,
    val text: String,
    val key: String,
    val spaceBefore: Boolean,
    val breakBefore: Boolean,
    val literal: Boolean,
    val link: LinkSpan?,
) {
    fun isPunct(c: Char): Boolean = kind == TokenKind.PUNCT && text.length == 1 && text[0] == c

    /** Only ASCII digits, as typed: "12", not "12.10". */
    val isPlainNumber: Boolean get() = kind == TokenKind.NUMBER && text.all { it in '0'..'9' }
}

/** Comparison keys shared by the tokenizer and the language packs. */
internal object Keys {
    private const val APOSTROPHES = "'’ʼ‘`"

    fun of(word: String): String {
        val sb = StringBuilder(word.length)
        for (ch in word) {
            if (ch in APOSTROPHES || Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            val lower = ch.lowercaseChar()
            sb.append(if (lower == 'ё') 'е' else lower)
        }
        return sb.toString()
    }

    fun isApostrophe(cp: Int): Boolean = cp < Char.MAX_VALUE.code && cp.toChar() in APOSTROPHES
}

/** Literal ranges in original coordinates, clamped to the input; empty or reversed ranges are ignored. */
internal class LiteralRanges(ranges: List<IntRange>, inputLength: Int) {
    private val valid: List<IntRange> = ranges
        .filter { !it.isEmpty() && it.last >= 0 && it.first < inputLength }
        .map { it.first.coerceAtLeast(0)..it.last.coerceAtMost(inputLength - 1) }

    val isEmpty: Boolean get() = valid.isEmpty()

    /** Whether original chars `[from, to)` touch any literal range. */
    fun overlaps(from: Int, to: Int): Boolean = valid.any { it.first < to && it.last >= from }
}

/** Hand-written tokenizer (tech plan §9.1, step 2); links found earlier become single protected tokens. */
internal object Tokenizer {
    fun tokenize(norm: NormalizedText, links: List<LinkSpan>, literal: LiteralRanges): List<Token> {
        val text = norm.text
        val tokens = ArrayList<Token>()
        var i = 0
        var linkIndex = 0
        while (i < text.length) {
            if (text[i] == ' ') {
                i++
                continue
            }
            while (linkIndex < links.size && links[linkIndex].start < i) linkIndex++
            val link = links.getOrNull(linkIndex)?.takeIf { it.start == i }
            val limit = links.getOrNull(linkIndex)?.start ?: text.length
            val end: Int
            val kind: TokenKind
            if (link != null) {
                kind = TokenKind.LINK
                end = link.end
                linkIndex++
            } else {
                val nameEnd = nameEnd(text, i, limit)
                kind = when {
                    nameEnd > 0 -> if (text[i] == '#') TokenKind.PROJECT else TokenKind.TAG
                    text[i] in '0'..'9' -> TokenKind.NUMBER
                    isWordStart(text.codePointAt(i)) -> TokenKind.WORD
                    else -> TokenKind.PUNCT
                }
                end = when (kind) {
                    TokenKind.PROJECT, TokenKind.TAG -> nameEnd
                    TokenKind.NUMBER -> numberEnd(text, i, limit)
                    TokenKind.WORD -> wordEnd(text, i, limit)
                    else -> i + Character.charCount(text.codePointAt(i))
                }
            }
            tokens += token(norm, tokens.size, kind, i, end, link, literal)
            i = end
        }
        return tokens
    }

    private fun token(norm: NormalizedText, index: Int, kind: TokenKind, start: Int, end: Int, link: LinkSpan?, literal: LiteralRanges): Token {
        val text = norm.text.substring(start, end)
        val spaceBefore = start > 0 && norm.text[start - 1] == ' '
        return Token(
            index = index,
            kind = kind,
            start = start,
            end = end,
            text = text,
            key = if (kind == TokenKind.WORD) Keys.of(text) else text,
            spaceBefore = spaceBefore,
            breakBefore = spaceBefore && norm.isLineBreak(start - 1),
            literal = !literal.isEmpty && literal.overlaps(norm.originalStart(start), norm.originalEnd(end - 1)),
            link = link,
        )
    }

    /** End of `#name` / `@name` at [start] (letters, digits, `_`, `-`, at least one letter), or -1. */
    private fun nameEnd(text: String, start: Int, limit: Int): Int {
        val c = text[start]
        if (c != '#' && c != '@') return -1
        if (start > 0 && isNameChar(text.codePointBefore(start))) return -1
        var j = start + 1
        var hasLetter = false
        while (j < limit) {
            val cp = text.codePointAt(j)
            if (!isNameChar(cp) && cp != '-'.code) break
            hasLetter = hasLetter || Character.isLetter(cp)
            j += Character.charCount(cp)
        }
        while (j > start + 1 && (text[j - 1] == '-' || text[j - 1] == '_')) j--
        return if (hasLetter && j > start + 1) j else -1
    }

    private fun numberEnd(text: String, start: Int, limit: Int): Int {
        var j = start + 1
        while (j < limit) {
            val c = text[j]
            j += when {
                c in '0'..'9' -> 1
                c in ".:/,-" && j + 1 < limit && text[j + 1] in '0'..'9' -> 2
                else -> break
            }
        }
        return j
    }

    private fun wordEnd(text: String, start: Int, limit: Int): Int {
        var j = start + Character.charCount(text.codePointAt(start))
        while (j < limit) {
            val cp = text.codePointAt(j)
            val joinsLetters = (Keys.isApostrophe(cp) || cp == '-'.code) &&
                j + 1 < limit && Character.isLetter(text.codePointAt(j + 1))
            if (!isNameChar(cp) && !joinsLetters) break
            j += Character.charCount(cp)
        }
        return j
    }

    private fun isWordStart(cp: Int): Boolean = Character.isLetter(cp) || (Character.isDigit(cp) && cp > 0x7F)

    private fun isNameChar(cp: Int): Boolean = Character.isLetterOrDigit(cp) || cp == '_'.code || isMark(cp)

    private fun isMark(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }
}
