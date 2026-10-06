package app.tasker.core.parser

import app.tasker.core.model.Estimate

/**
 * Rule-based parser of a task input line (tech plan §9; CAP-2…5, CAP-8, EXC-5). Pure and deterministic: the result depends
 * only on the input, [ParseContext] and the enabled languages. Thread-safe; create it once and reuse it.
 *
 * @param enabledLanguages language packs to use, out of "ru", "uk" and "en"; unknown codes are ignored. Numeric dates and
 *   times, `#project`, `@tag`, estimates and links work with any set, even an empty one.
 */
class InputParser(enabledLanguages: Set<String> = setOf("ru", "uk", "en")) {
    private val packs: List<LanguagePack> = LanguagePacks.all.filter { it.code in enabledLanguages }

    fun parse(input: String, context: ParseContext): ParseResult = ParseSession(input, context, packs).run()
}

/** One run of the pipeline of tech plan §9.1 over one input. */
internal class ParseSession(
    private val input: String,
    private val context: ParseContext,
    enabledPacks: List<LanguagePack>,
) : TokenKeys {
    private val norm = TextNormalizer.normalize(input)
    private val literal = LiteralRanges(context.literalRanges, input.length)
    private val tokens = Tokenizer.tokenize(norm, LinkDetector.detect(norm.text), literal)
    private val packs: List<LanguagePack> = rankPacks(enabledPacks) + LanguagePacks.Neutral

    /** Tokens still free for parsing: not literal, not a link or name, not taken by an earlier step. */
    private val usable = BooleanArray(tokens.size) {
        val token = tokens[it]
        !token.literal && (token.kind == TokenKind.WORD || token.kind == TokenKind.NUMBER || token.kind == TokenKind.PUNCT)
    }
    private val removed = BooleanArray(tokens.size)
    private val anchors = BooleanArray(tokens.size)
    private val fields = ArrayList<ParsedField>()

    /**
     * Orders the packs by how much the input looks like their language: distinctive letters plus words that only one
     * enabled pack knows. Words shared by ru and uk ("завтра", "до") are then attributed to the language of the input;
     * ties keep the ru, uk, en order.
     */
    private fun rankPacks(enabled: List<LanguagePack>): List<LanguagePack> {
        if (enabled.size < 2) return enabled
        val scores = IntArray(enabled.size)
        for (c in norm.text) {
            val lower = c.lowercaseChar()
            for (p in enabled.indices) if (lower in enabled[p].distinctiveLetters) scores[p]++
        }
        for (token in tokens) {
            if (token.kind != TokenKind.WORD) continue
            val owners = enabled.indices.filter { token.key in enabled[it].vocabulary }
            if (owners.size == 1) scores[owners[0]]++
        }
        return enabled.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it }).map { enabled[it] }
    }

    override fun keyAt(index: Int): String? = if (index in tokens.indices && usable[index]) tokens[index].key else null

    override fun continues(index: Int): Boolean = index < tokens.size && !tokens[index].breakBefore

    fun run(): ParseResult {
        extractAlreadyDone()
        extractProjectAndTags()
        extractEstimate()
        extractTemporal()
        val links = extractLinks()
        val title = title(links)
        fields.sortBy { it.ranges.first().first }
        return ParseResult(input, title, fields.toList(), links)
    }

    /** CAP-8: "уже сделал …", "сделано: …", "done: …" — only at the very start. */
    private fun extractAlreadyDone() {
        for (pack in packs) {
            val match = pack.alreadyDoneTrie.match(0, this) ?: continue
            var last = match.length - 1
            val colon = tokens.getOrNull(last + 1)?.takeIf { usable[it.index] && it.isPunct(':') && !it.breakBefore }
            if (match.value && colon == null) continue
            if (colon != null) last++
            addField(ParsedValue.AlreadyDone, listOf(0..last), pack.code, anchor = false)
            return
        }
    }

    private fun extractProjectAndTags() {
        var hasProject = false
        for (token in tokens) {
            if (token.literal) continue
            val name = token.text.substring(1)
            if (token.kind == TokenKind.PROJECT && !hasProject) {
                hasProject = true
                addField(project(name), listOf(token.index..token.index), null, anchor = false)
            } else if (token.kind == TokenKind.TAG) {
                addField(ParsedValue.Tag(name), listOf(token.index..token.index), null, anchor = false)
            }
        }
    }

    private fun project(name: String): ParsedValue.Project {
        val key = projectKey(name)
        val known = context.knownProjects.firstOrNull { projectKey(it) == key }
        return ParsedValue.Project(known ?: name, isNew = known == null)
    }

    /** Rule 11: a standalone `S`, `M`, `L` or Cyrillic `М`; "S-class", "M&M" or the initial in "М. Горький" are not estimates. */
    private fun extractEstimate() {
        for (token in tokens) {
            if (!usable[token.index] || token.kind != TokenKind.WORD) continue
            val estimate = ESTIMATES[token.text] ?: continue
            if (!isStandalone(token)) continue
            addField(ParsedValue.Size(estimate), listOf(token.index..token.index), null, anchor = false)
            return
        }
    }

    private fun isStandalone(token: Token): Boolean {
        val before = tokens.getOrNull(token.index - 1)
        val after = tokens.getOrNull(token.index + 1)
        val leftFree = before == null || token.spaceBefore || before.text in ESTIMATE_OPENERS
        val rightFree = after == null || after.spaceBefore || after.text in ESTIMATE_CLOSERS || endsLine(after)
        return leftFree && rightFree
    }

    /** A dot that ends the input or its line: "Задача L." (a dot before more text may be an initial). */
    private fun endsLine(token: Token): Boolean {
        val following = tokens.getOrNull(token.index + 1)
        return token.isPunct('.') && (following == null || following.breakBefore)
    }

    private fun extractTemporal() {
        val atoms = TemporalScanner(packs, tokens, usable, context).scan()
        val result = TemporalResolver(tokens, atoms, packs, context).resolve()
        for (field in listOfNotNull(result.deadline, result.planDate, result.horizon)) {
            addField(field.value, field.atoms.map { it.first..it.last }, field.language, anchor = true)
        }
    }

    /** EXC-5: URLs leave the title, e-mails stay in it; both are reported. A literal link is plain text. */
    private fun extractLinks(): List<ParsedLink> {
        val links = ArrayList<ParsedLink>()
        for (token in tokens) {
            val link = token.link ?: continue
            if (token.literal) continue
            links += ParsedLink(link.target, originalRanges(listOf(token.index..token.index)).first(), link.kind)
            if (link.kind == LinkKind.URL) {
                removed[token.index] = true
                anchors[token.index] = true
            }
        }
        return links
    }

    /** Interpretations 19 and 20: an empty title becomes the shortened first URL or the whole input. */
    private fun title(links: List<ParsedLink>): String {
        val dangling = packs.flatMapTo(HashSet()) { it.danglingKeys }
        val built = TitleBuilder(tokens, removed, anchors, dangling).build()
        val title = when {
            hasContent(built) -> built
            else -> links.firstOrNull { it.kind == LinkKind.URL }?.let { UrlShortener.shorten(it.url) } ?: norm.text
        }
        return title.ifBlank { input.trim() }
    }

    /** Token index spans → ranges in the original input; neighbouring spans of one field are joined. */
    private fun addField(value: ParsedValue, spans: List<IntRange>, language: String?, anchor: Boolean) {
        for (span in spans) {
            for (i in span) {
                removed[i] = true
                usable[i] = false
                if (anchor) anchors[i] = true
            }
        }
        fields += ParsedField(value, originalRanges(spans), language)
    }

    private fun originalRanges(spans: List<IntRange>): List<IntRange> {
        val merged = ArrayList<IntRange>()
        for (span in spans.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && span.first == last.last + 1 && !literalGap(last.last, span.first)) {
                merged[merged.size - 1] = last.first..span.last
            } else {
                merged += span
            }
        }
        return merged.map { norm.originalStart(tokens[it.first].start) until norm.originalEnd(tokens[it.last].end - 1) }
    }

    /** Whitespace between two tokens that the user marked literal keeps the fragments apart. */
    private fun literalGap(left: Int, right: Int): Boolean {
        val from = norm.originalEnd(tokens[left].end - 1)
        val to = norm.originalStart(tokens[right].start)
        return from < to && literal.overlaps(from, to)
    }

    private fun hasContent(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val blank = Character.isWhitespace(cp) || Character.isSpaceChar(cp)
            if (!blank && Character.getType(cp) !in PUNCTUATION_TYPES) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private companion object {
        val ESTIMATES = mapOf(
            "S" to Estimate.S,
            "M" to Estimate.M,
            "L" to Estimate.L,
            "М" to Estimate.M,
        )
        val ESTIMATE_OPENERS = setOf("(", "[", "«", "\"", "“")
        val ESTIMATE_CLOSERS = setOf(",", ";", ")", "]", "»", "\"", "”", "!", "?")
        val PUNCTUATION_TYPES = setOf(
            Character.CONNECTOR_PUNCTUATION,
            Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION,
            Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION,
            Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
        ).map { it.toInt() }.toSet()

        /** Project names compare case-insensitively, "ё" = "е", ignoring spaces, `_` and `-`. */
        fun projectKey(name: String): String = Keys.of(name).filterNot { it == ' ' || it == '_' || it == '-' }
    }
}
