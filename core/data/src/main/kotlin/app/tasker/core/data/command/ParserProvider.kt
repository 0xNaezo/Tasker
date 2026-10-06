package app.tasker.core.data.command

import android.os.LocaleList
import app.tasker.core.model.AppSettings
import app.tasker.core.parser.InputParser
import app.tasker.core.parser.SlashDateOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Input parsers per language set (tech plan §9), created once and reused while typing. */
@Singleton
class ParserProvider @Inject constructor() {
    private val parsers = ConcurrentHashMap<Set<String>, InputParser>()

    fun parser(settings: AppSettings): InputParser {
        val languages = languages(settings)
        return parsers.getOrPut(languages) { InputParser(languages) }
    }

    /**
     * Parsing languages: the user's choice, otherwise the system languages (§7.7). English is always on; Russian and
     * Ukrainian come together, because people who use one of them commonly type in both.
     */
    fun languages(settings: AppSettings): Set<String> {
        settings.parserLanguages?.filter { it in SUPPORTED }?.takeIf { it.isNotEmpty() }?.let { return it.toSortedSet() }
        val system = systemLanguages()
        val result = sortedSetOf(ENGLISH)
        if (system.any { it in SLAVIC }) result += SLAVIC
        return result
    }

    fun slashDateOrder(locale: Locale = Locale.getDefault()): SlashDateOrder =
        if (locale.country in MONTH_FIRST_REGIONS) SlashDateOrder.MONTH_DAY else SlashDateOrder.DAY_MONTH

    private fun systemLanguages(): Set<String> {
        val list = LocaleList.getDefault()
        return (0 until list.size()).mapTo(HashSet()) { list[it].language }
    }

    companion object {
        private const val ENGLISH = "en"
        private val SLAVIC = setOf("ru", "uk")
        val SUPPORTED = setOf("ru", "uk", "en")
        private val MONTH_FIRST_REGIONS = setOf("US", "PH", "FM", "MH", "PW")
    }
}
