package app.tasker.core.parser

/** A link in the normalized text, `[start, end)`. [target] is the normalized URL or a "mailto:" address. */
internal class LinkSpan(val start: Int, val end: Int, val kind: LinkKind, val target: String)

/**
 * Protective extraction (tech plan §9.1, step 3): URLs and e-mails are found before tokenization, so dates, tags and
 * numbers inside them are never parsed. Hand-written linear scanning, no regular expressions.
 */
internal object LinkDetector {
    private const val MAX_SCHEME_LENGTH = 20
    private const val HTTPS = "https://"
    private const val TRAILING_PUNCTUATION = ".,;:!?…'\"’”»)]}>"
    private const val URL_STOP_CHARS = "<>\"«»“”"
    private const val DOMAIN_OPENERS = "([{«\"'“‘<"

    /** Top-level domains accepted without a scheme or "www."; a short list keeps file names like "notes.md" plain text. */
    private val BARE_DOMAIN_TLDS = setOf(
        "com", "org", "net", "edu", "gov", "io", "dev", "app", "info", "me", "co",
        "ru", "ua", "by", "kz", "eu", "de", "uk", "us", "рф", "укр",
    )

    fun detect(text: String): List<LinkSpan> {
        val found = Found(text.length)
        findExplicitUrls(text, found)
        findEmails(text, found)
        findBareDomains(text, found)
        found.spans.sortBy { it.start }
        return found.spans
    }

    private class Found(length: Int) {
        val spans = ArrayList<LinkSpan>()
        val covered = BooleanArray(length)

        fun add(span: LinkSpan) {
            spans += span
            for (k in span.start until span.end) covered[k] = true
        }

        fun isFree(start: Int, end: Int): Boolean = (start until end).none { covered[it] }
    }

    private fun findExplicitUrls(text: String, found: Found) {
        var i = 0
        while (i < text.length) {
            val span = if (startsWord(text, i)) explicitUrlAt(text, i) else null
            if (span != null) {
                found.add(span)
                i = span.end
            } else {
                i++
            }
        }
    }

    private fun explicitUrlAt(text: String, start: Int): LinkSpan? {
        val afterScheme = schemeEnd(text, start)
        val hostStart = when {
            afterScheme > 0 -> afterScheme
            text.regionMatches(start, "www.", 0, 4, ignoreCase = true) -> start + 4
            else -> return null
        }
        if (hostStart >= text.length || !text[hostStart].isLetterOrDigit()) return null
        val end = urlEnd(text, start, hostStart)
        if (end <= hostStart) return null
        val raw = text.substring(start, end)
        return LinkSpan(start, end, LinkKind.URL, if (afterScheme > 0) raw else HTTPS + raw)
    }

    /** Index right after "scheme://", or -1. */
    private fun schemeEnd(text: String, start: Int): Int {
        if (!isAsciiLetter(text[start])) return -1
        var j = start + 1
        while (j < text.length && j - start <= MAX_SCHEME_LENGTH && isSchemeChar(text[j])) j++
        return if (text.startsWith("://", j)) j + 3 else -1
    }

    private fun findEmails(text: String, found: Found) {
        var at = text.indexOf('@')
        while (at >= 0) {
            val span = if (found.covered[at]) null else emailAround(text, at, found)
            if (span != null) found.add(span)
            at = text.indexOf('@', (span?.end ?: at) + 1)
        }
    }

    private fun emailAround(text: String, at: Int, found: Found): LinkSpan? {
        var localStart = at
        while (localStart > 0 && isEmailLocalChar(text[localStart - 1]) && !found.covered[localStart - 1]) localStart--
        while (localStart < at && text[localStart] == '.') localStart++
        if (localStart == at) return null
        val domainEnd = domainEnd(text, at + 1)
        if (domainEnd < 0 || !found.isFree(localStart, domainEnd)) return null
        val address = text.substring(localStart, domainEnd)
        return LinkSpan(localStart, domainEnd, LinkKind.EMAIL, "mailto:$address")
    }

    /** End of a dotted domain whose last label is at least two letters, or -1. */
    private fun domainEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '-' || text[end] == '.')) end++
        while (end > start && (text[end - 1] == '.' || text[end - 1] == '-')) end--
        if (end <= start) return -1
        val labels = text.substring(start, end).split('.')
        val valid = labels.size >= 2 &&
            labels.all { it.isNotEmpty() && !it.startsWith('-') && !it.endsWith('-') } &&
            labels.last().length >= 2 &&
            labels.last().all { it.isLetter() }
        return if (valid) end else -1
    }

    private fun findBareDomains(text: String, found: Found) {
        var i = 0
        while (i < text.length) {
            val span = if (!found.covered[i] && startsBareDomain(text, i)) bareDomainAt(text, i) else null
            if (span != null && found.isFree(span.start, span.end)) {
                found.add(span)
                i = span.end
            } else {
                i++
            }
        }
    }

    private fun bareDomainAt(text: String, start: Int): LinkSpan? {
        var j = start
        var labels = 0
        var lastLabelStart = start
        while (true) {
            val labelStart = j
            while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '-')) j++
            if (j == labelStart) return null
            labels++
            lastLabelStart = labelStart
            if (j + 1 < text.length && text[j] == '.' && text[j + 1].isLetterOrDigit()) j++ else break
        }
        if (labels < 2 || text.substring(lastLabelStart, j).lowercase() !in BARE_DOMAIN_TLDS) return null
        val next = if (j < text.length) text[j] else ' '
        val end = when {
            next == '/' || next == '?' || next == '#' || next == ':' -> urlEnd(text, start, j)
            next.isWhitespace() || next in TRAILING_PUNCTUATION -> j
            else -> return null
        }
        return LinkSpan(start, end, LinkKind.URL, HTTPS + text.substring(start, end))
    }

    /**
     * Scans to the end of a URL starting at [start] and drops trailing punctuation that belongs to the sentence; a closing
     * bracket stays when the URL opened it ("…/Foo_(bar)").
     */
    private fun urlEnd(text: String, start: Int, from: Int): Int {
        var end = from
        var round = 0
        var square = 0
        while (end < text.length && !text[end].isWhitespace() && text[end] !in URL_STOP_CHARS && !text[end].isISOControl()) end++
        for (k in start until end) {
            when (text[k]) {
                '(' -> round++
                ')' -> round--
                '[' -> square++
                ']' -> square--
            }
        }
        while (end > from) {
            val c = text[end - 1]
            if (c !in TRAILING_PUNCTUATION) break
            if (c == ')' && round >= 0) break
            if (c == ']' && square >= 0) break
            if (c == ')') round++
            if (c == ']') square++
            end--
        }
        return end
    }

    private fun startsWord(text: String, i: Int): Boolean {
        if (i == 0) return true
        val prev = text[i - 1]
        return !prev.isLetterOrDigit() && prev !in "@._-/+"
    }

    private fun startsBareDomain(text: String, i: Int): Boolean =
        text[i].isLetterOrDigit() && (i == 0 || text[i - 1].isWhitespace() || text[i - 1] in DOMAIN_OPENERS)

    private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    private fun isSchemeChar(c: Char): Boolean = isAsciiLetter(c) || c in '0'..'9' || c == '+' || c == '.' || c == '-'

    private fun isEmailLocalChar(c: Char): Boolean = isAsciiLetter(c) || c in '0'..'9' || c in "._%+-"
}

/** Interpretation 20: a URL as a task title is its host without "www." plus the path, without scheme, query or trailing slash. */
internal object UrlShortener {
    fun shorten(url: String): String {
        val rest = url.substringAfter("://", url).substringBefore('#').substringBefore('?')
        val slash = rest.indexOf('/')
        var host = (if (slash >= 0) rest.substring(0, slash) else rest).substringAfterLast('@')
        if (host.startsWith("www.", ignoreCase = true)) host = host.substring(4)
        val path = if (slash >= 0) percentDecode(rest.substring(slash).trimEnd('/')) else ""
        return (host + path).ifBlank { url }
    }

    /** Decodes runs of `%XX` UTF-8 escapes; a run that is not valid UTF-8 is kept as it is. */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (hexByteAt(s, i) < 0) {
                out.append(s[i])
                i++
                continue
            }
            val runStart = i
            val bytes = java.io.ByteArrayOutputStream()
            while (hexByteAt(s, i) >= 0) {
                bytes.write(hexByteAt(s, i))
                i += 3
            }
            val decoded = String(bytes.toByteArray(), Charsets.UTF_8)
            out.append(if ('�' in decoded || decoded.any { it.isISOControl() }) s.substring(runStart, i) else decoded)
        }
        return out.toString()
    }

    /** Value of the escape `%XX` at [i], or -1. */
    private fun hexByteAt(s: String, i: Int): Int {
        if (i + 2 >= s.length || s[i] != '%') return -1
        val hi = Character.digit(s[i + 1], 16)
        val lo = Character.digit(s[i + 2], 16)
        return if (hi < 0 || lo < 0) -1 else hi * 16 + lo
    }
}
