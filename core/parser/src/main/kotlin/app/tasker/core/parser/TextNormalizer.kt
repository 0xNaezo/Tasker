package app.tasker.core.parser

import java.text.Normalizer

/**
 * Input after NFC normalization and whitespace collapsing (tech plan §9.1, step 1), with a map back to the original.
 * Every normalized char `i` comes from the original chars `[originalStart(i), originalEnd(i))`; a collapsed space covers
 * the whole whitespace run it replaced.
 */
internal class NormalizedText(
    val text: String,
    private val starts: IntArray,
    private val ends: IntArray,
    private val lineBreaks: BooleanArray,
) {
    val length: Int get() = text.length

    fun originalStart(index: Int): Int = starts[index]

    /** Exclusive end in the original string. */
    fun originalEnd(index: Int): Int = ends[index]

    /** The collapsed space at [index] replaced whitespace that contained a line break. */
    fun isLineBreak(index: Int): Boolean = lineBreaks[index]
}

internal object TextNormalizer {
    private const val SPACE = ' '

    fun normalize(input: String): NormalizedText {
        val nfc = composed(input)
        val size = nfc.text.length
        val out = StringBuilder(size)
        val starts = IntArray(size)
        val ends = IntArray(size)
        val breaks = BooleanArray(size)
        var gapStart = -1
        var gapEnd = -1
        var gapBreak = false
        for (i in 0 until size) {
            val c = nfc.text[i]
            if (c.isWhitespace()) {
                if (out.isNotEmpty()) {
                    if (gapStart < 0) gapStart = nfc.starts[i]
                    gapEnd = nfc.ends[i]
                    gapBreak = gapBreak || isLineBreakChar(c)
                }
                continue
            }
            if (gapStart >= 0) {
                val k = out.length
                out.append(SPACE)
                starts[k] = gapStart
                ends[k] = gapEnd
                breaks[k] = gapBreak
                gapStart = -1
                gapBreak = false
            }
            val k = out.length
            out.append(c)
            starts[k] = nfc.starts[i]
            ends[k] = nfc.ends[i]
        }
        val length = out.length
        return NormalizedText(out.toString(), starts.copyOf(length), ends.copyOf(length), breaks.copyOf(length))
    }

    private fun isLineBreakChar(c: Char): Boolean =
        c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' || c == '\u0085' || c == ' ' || c == ' '

    private class Mapped(val text: String, val starts: IntArray, val ends: IntArray)

    /**
     * NFC with an offset map. Composition never crosses the start of a combining sequence, so each sequence (a starter with
     * the marks after it) is normalized on its own; when NFC changes a sequence, all its output chars map to the whole
     * original sequence.
     */
    private fun composed(input: String): Mapped {
        if (Normalizer.isNormalized(input, Normalizer.Form.NFC)) {
            return Mapped(input, IntArray(input.length) { it }, IntArray(input.length) { it + 1 })
        }
        val out = StringBuilder(input.length + 8)
        var starts = IntArray(input.length + 8)
        var ends = IntArray(input.length + 8)
        var chunkStart = 0
        while (chunkStart < input.length) {
            val chunkEnd = sequenceEnd(input, chunkStart)
            val chunk = input.substring(chunkStart, chunkEnd)
            val normalized = Normalizer.normalize(chunk, Normalizer.Form.NFC)
            if (out.length + normalized.length > starts.size) {
                val grown = (starts.size * 2).coerceAtLeast(out.length + normalized.length)
                starts = starts.copyOf(grown)
                ends = ends.copyOf(grown)
            }
            val unchanged = normalized == chunk
            for (k in normalized.indices) {
                val at = out.length + k
                starts[at] = if (unchanged) chunkStart + k else chunkStart
                ends[at] = if (unchanged) chunkStart + k + 1 else chunkEnd
            }
            out.append(normalized)
            chunkStart = chunkEnd
        }
        return Mapped(out.toString(), starts.copyOf(out.length), ends.copyOf(out.length))
    }

    private fun sequenceEnd(s: String, start: Int): Int {
        var i = start + Character.charCount(s.codePointAt(start))
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!continuesSequence(cp)) break
            i += Character.charCount(cp)
        }
        return i
    }

    /** Combining marks and the Hangul vowel/final jamo that NFC composes with the preceding char. */
    private fun continuesSequence(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            cp in 0x1160..0x11FF ||
            cp in 0xD7B0..0xD7FF
    }
}
