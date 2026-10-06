package app.tasker.core.data.search

import java.text.Normalizer
import java.util.Locale
import org.tartarus.snowball.SnowballStemmer
import org.tartarus.snowball.ext.englishStemmer
import org.tartarus.snowball.ext.russianStemmer

/**
 * A word of the original text with its position and its search form; [variants] are the extra index forms of a
 * Ukrainian word.
 */
data class SearchToken(val range: IntRange, val form: String, val variants: List<String> = emptyList())

/**
 * Text normalization for the FTS index and queries (tech plan §15): lower case, "ё" → "е", word stems.
 * The platform SQLite cannot load a custom tokenizer, so the index stores normalized stems and every query term goes
 * through the same function. Each word gets exactly one form, so queries need no OR and work with both the standard
 * and the enhanced FTS query syntax:
 * - Latin words — Snowball English;
 * - Cyrillic words with Ukrainian letters (і, ї, є, ґ) or an apostrophe — a light Ukrainian suffix stripper;
 * - other Cyrillic words — Snowball Russian.
 * Query terms are matched as prefixes, which is also the fallback for Ukrainian forms the light stemmer misses.
 */
object SearchNormalizer {
    /**
     * Normalized forms of [text] joined with spaces: the content of an FTS column. A Ukrainian word is indexed with every
     * plausible stem, so a query stem that cuts a different ending still finds it by prefix.
     */
    fun indexText(text: String?): String =
        if (text.isNullOrBlank()) "" else tokens(text).flatMap { indexForms(it) }.distinct().joinToString(" ")

    private fun indexForms(token: SearchToken): List<String> = token.variants.ifEmpty { listOf(token.form) }

    /** Search tokens of [text] with their ranges in the original string (for highlighting). */
    fun tokens(text: String): List<SearchToken> {
        val result = ArrayList<SearchToken>()
        var start = -1
        for (i in 0..text.length) {
            val inWord = i < text.length && isWordChar(text[i], text, i)
            if (inWord && start < 0) start = i
            if (!inWord && start >= 0) {
                val word = text.substring(start, i)
                val form = form(word)
                if (form.isNotEmpty()) result += SearchToken(start until i, form, ukrainianVariants(word))
                start = -1
            }
        }
        return result
    }

    /**
     * FTS MATCH expression for a user query: every term as a prefix, all terms required.
     * Returns null when the query has no searchable terms.
     */
    fun matchQuery(query: String): String? {
        val forms = tokens(query).map { it.form }.distinct()
        if (forms.isEmpty()) return null
        return forms.joinToString(" ") { "$it*" }
    }

    /** Ranges of [text] whose words match any term of [query] (same normalization, prefix match). */
    fun highlights(text: String, query: String): List<IntRange> {
        val terms = tokens(query).map { it.form }.distinct()
        if (terms.isEmpty()) return emptyList()
        return tokens(text).filter { token -> terms.any { term -> indexForms(token).any { it.startsWith(term) } } }.map { it.range }
    }

    private fun ukrainianVariants(word: String): List<String> {
        val normalized = normalize(word)
        if (!isUkrainian(word, normalized)) return emptyList()
        return (UkrainianLightStemmer.candidates(normalized) + normalized).distinct()
    }

    private fun normalize(word: String): String = Normalizer.normalize(word, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace("'", "")
        .replace("’", "")
        .replace("ʼ", "")

    private fun isCyrillic(normalized: String) = normalized.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC }

    private fun isUkrainian(word: String, normalized: String) =
        isCyrillic(normalized) && (normalized.any { it in UKRAINIAN_LETTERS } || word.any { it in APOSTROPHES })

    fun form(word: String): String {
        val normalized = normalize(word)
        if (normalized.isEmpty()) return ""
        return when {
            isUkrainian(word, normalized) -> UkrainianLightStemmer.stem(normalized)
            isCyrillic(normalized) -> snowball(russian, normalized)
            normalized.any { it in 'a'..'z' } -> snowball(english, normalized)
            else -> normalized
        }
    }

    private fun isWordChar(c: Char, text: String, index: Int): Boolean {
        if (c.isLetterOrDigit()) return true
        // An apostrophe inside a word belongs to it (Ukrainian "п'ять", English "don't").
        return c in APOSTROPHES && index > 0 && index + 1 < text.length && text[index - 1].isLetter() && text[index + 1].isLetter()
    }

    // Snowball stemmers keep state between calls; one instance per thread.
    private val russian = ThreadLocal.withInitial<SnowballStemmer> { russianStemmer() }
    private val english = ThreadLocal.withInitial<SnowballStemmer> { englishStemmer() }

    private fun snowball(stemmer: ThreadLocal<SnowballStemmer>, word: String): String {
        if (word.length < MIN_STEM_INPUT) return word
        val instance = checkNotNull(stemmer.get())
        instance.current = word
        instance.stem()
        return instance.current.ifEmpty { word }
    }

    private val UKRAINIAN_LETTERS = setOf('і', 'ї', 'є', 'ґ')
    private val APOSTROPHES = setOf('\'', '’', 'ʼ')
    private const val MIN_STEM_INPUT = 3
}

/**
 * Minimal Ukrainian stemmer: strips the longest known inflectional ending while keeping a stem of at least three
 * letters. Recall gaps are covered by prefix matching of query terms.
 */
internal object UkrainianLightStemmer {
    private val endings = listOf(
        // verbs
        "увалися", "увалася", "увалось", "ювалися", "увався", "ювався", "увати", "ювати", "уватися", "ються", "ється",
        "ишся", "ешся", "ться", "тися", "ували", "ювали", "ував", "ював", "ують", "юють", "ємо", "емо", "имо", "ете", "єте",
        "ите", "ать", "ять", "уть", "ють", "ити", "ати", "яти", "іти", "ила", "ило", "или", "ала", "ало", "али", "ла",
        "ло", "ли", "ти", "ть", "еш", "єш", "иш", "ся", "сь",
        // adjectives and pronouns
        "ього", "ьому", "ого", "ому", "ими", "іми", "ими", "ій", "ий", "им", "ім", "их", "іх", "ої", "ою", "ую", "юю",
        "яя", "еє", "ее",
        // nouns
        "ами", "ями", "ах", "ях", "ам", "ям", "ом", "ем", "єм", "ів", "їв", "ей", "ею", "єю", "ові", "еві", "єві",
        "а", "я", "о", "е", "є", "и", "і", "ї", "у", "ю", "ь", "й",
    ).distinct().sortedByDescending { it.length }

    private const val MIN_STEM = 3

    /** The most aggressive stem: used for queries, so that it is a prefix of the indexed variants. */
    fun stem(word: String): String = candidates(word).firstOrNull() ?: word

    /** Every stem obtained by cutting a known ending, shortest first. */
    fun candidates(word: String): List<String> =
        endings.filter { word.length - it.length >= MIN_STEM && word.endsWith(it) }.map { word.dropLast(it.length) }
}
