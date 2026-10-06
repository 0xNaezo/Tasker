package app.tasker.core.domain.similar

import java.text.Normalizer
import java.util.Locale

/**
 * Lexical pre-selection of similar tasks on the device (tech plan §17.3): AI receives at most a handful of
 * similar completed tasks instead of the whole history.
 */
object SimilarTasks {
    data class Scored<T>(val item: T, val score: Double)

    fun tokens(text: String): Set<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT).replace('ё', 'е')
        return normalized.split(NON_WORD).filter { it.length >= MIN_TOKEN }.map { stem(it) }.toSet()
    }

    /** Crude prefix stem: enough to match word forms for pre-selection; quality search uses the FTS index. */
    private fun stem(token: String): String = if (token.length > STEM_LENGTH) token.take(STEM_LENGTH) else token

    fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }
        return intersection.toDouble() / (a.size + b.size - intersection)
    }

    fun <T> pick(text: String, candidates: Collection<T>, limit: Int, textOf: (T) -> String): List<Scored<T>> {
        val query = tokens(text)
        if (query.isEmpty()) return emptyList()
        return candidates.asSequence()
            .map { Scored(it, similarity(query, tokens(textOf(it)))) }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private const val MIN_TOKEN = 2
    private const val STEM_LENGTH = 5
}
