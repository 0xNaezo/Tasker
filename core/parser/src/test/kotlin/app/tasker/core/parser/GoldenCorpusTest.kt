package app.tasker.core.parser

import com.google.common.truth.Truth.assertWithMessage
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Test

/**
 * Golden corpus (tech plan §9.7): `src/test/resources/golden/{ru,uk,en}.tsv`, at least 150 cases per language.
 * Every line is checked and all failing lines are reported together. The file header describes the format.
 */
class GoldenCorpusTest {
    private class Item(val key: String, val text: String?, val language: String?) {
        override fun toString(): String = buildString {
            append(key)
            if (text != null) append(" [").append(text).append(']')
            if (language != null) append(" {").append(language).append('}')
        }
    }

    private class Case(
        val location: String,
        val input: String,
        val title: String,
        val items: List<Item>,
        val context: ParseContext,
        val languages: Set<String>,
    )

    private val parsers = HashMap<Set<String>, InputParser>()

    @Test
    fun everyGoldenLinePasses() {
        val failures = ArrayList<String>()
        val counts = LinkedHashMap<String, Int>()
        for (language in LANGUAGES) {
            val lines = readLines(language)
            var count = 0
            lines.forEachIndexed { index, line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
                count++
                val location = "$language.tsv:${index + 1}"
                val failure = runCatching { check(parseCase(location, line)) }
                    .getOrElse { "$location  malformed line: ${it.message}\n    $line" }
                if (failure != null) failures += failure
            }
            counts[language] = count
        }
        val summary = counts.entries.joinToString { "${it.key}: ${it.value}" }
        assertWithMessage("%s failing golden lines (%s):\n\n%s", failures.size, summary, failures.joinToString("\n\n"))
            .that(failures)
            .isEmpty()
        for ((language, count) in counts) {
            assertWithMessage("golden cases for %s", language).that(count).isAtLeast(MIN_CASES_PER_LANGUAGE)
        }
    }

    private fun readLines(language: String): List<String> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/golden/$language.tsv")) { "golden/$language.tsv is missing" }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
    }

    private fun check(case: Case): String? {
        val parser = parsers.getOrPut(case.languages) { InputParser(case.languages) }
        val result = parser.parse(case.input, case.context)
        val actual = actualItems(result)
        val problems = ArrayList<String>()
        if (result.title != case.title) problems += "title"
        if (!itemsMatch(case.items, actual)) problems += "fields"
        problems += ParseInvariants.problems(result)
        if (problems.isEmpty()) return null
        return buildString {
            append(case.location).append("  «").append(case.input).append("»  (").append(problems.joinToString()).append(")\n")
            append("    expected: «").append(case.title).append("» | ").append(case.items.render()).append('\n')
            append("    actual:   «").append(result.title).append("» | ").append(actual.render())
        }
    }

    private fun List<Item>.render(): String = if (isEmpty()) "-" else sortedBy { it.key }.joinToString("; ")

    private fun itemsMatch(expected: List<Item>, actual: List<Item>): Boolean {
        val e = expected.sortedBy { it.key }
        val a = actual.sortedBy { it.key }
        if (e.map { it.key } != a.map { it.key }) return false
        return e.zip(a).all { (exp, act) ->
            (exp.text == null || exp.text == act.text) && (exp.language == null || exp.language == act.language)
        }
    }

    private fun actualItems(result: ParseResult): List<Item> {
        val fields = result.fields.map { Item(keyOf(it.value), it.text(result.input), it.language ?: "null") }
        val links = result.links.map { link ->
            val key = if (link.kind == LinkKind.URL) "url=${link.url}" else "email=${link.url}"
            Item(key, result.input.substring(link.range), "null")
        }
        return fields + links
    }

    private fun keyOf(value: ParsedValue): String = when (value) {
        ParsedValue.AlreadyDone -> "done"
        is ParsedValue.Deadline -> "deadline=${value.date}" + (value.time?.let { " $it" } ?: "")
        is ParsedValue.PlanDate -> "plan=${value.date}"
        is ParsedValue.Horizon -> "bucket=${value.bucket.name.lowercase()}"
        is ParsedValue.Size -> "size=${value.estimate.name}"
        is ParsedValue.Project -> if (value.isNew) "newproject=${value.name}" else "project=${value.name}"
        is ParsedValue.Tag -> "tag=${value.name}"
    }

    private fun parseCase(location: String, line: String): Case {
        val columns = line.split('\t')
        require(columns.size in 3..4) { "expected 3 or 4 tab-separated columns, got ${columns.size}" }
        val input = unescape(columns[0])
        val options = if (columns.size == 4) parseOptions(columns[3]) else emptyMap()
        var today = ParserFixtures.TODAY
        var time = ParserFixtures.NOW.toLocalTime()
        var slash = SlashDateOrder.DAY_MONTH
        var projects = ParserFixtures.KNOWN_PROJECTS
        var languages = setOf("ru", "uk", "en")
        val literal = ArrayList<IntRange>()
        for ((key, values) in options) {
            for (value in values) {
                when (key) {
                    "today" -> today = LocalDate.parse(value)
                    "now" -> time = LocalTime.parse(value)
                    "slash" -> slash = if (value == "MD") SlashDateOrder.MONTH_DAY else SlashDateOrder.DAY_MONTH
                    "lit" -> literal += ParserFixtures.rangeOf(input, unescape(value))
                    "langs" -> languages = value.split(',').filter { it.isNotBlank() }.toSet()
                    "projects" -> projects = value.split(',').filter { it.isNotBlank() }.toSet()
                    else -> error("unknown option '$key'")
                }
            }
        }
        val context = ParseContext(
            now = today.atTime(time),
            today = today,
            slashDateOrder = slash,
            literalRanges = literal,
            knownProjects = projects,
        )
        return Case(location, input, unescape(columns[1]), parseItems(columns[2]), context, languages)
    }

    private fun parseOptions(column: String): Map<String, List<String>> = column.split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .groupBy({ it.substringBefore('=') }, { it.substringAfter('=') })

    private fun parseItems(column: String): List<Item> {
        if (column.trim() == "-") return emptyList()
        return column.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { raw ->
            var rest = raw
            var language: String? = null
            var text: String? = null
            if (rest.endsWith('}')) {
                language = rest.substringAfterLast('{').dropLast(1)
                rest = rest.substringBeforeLast('{').trim()
            }
            if (rest.endsWith(']')) {
                text = unescape(rest.substringAfter('[').dropLast(1))
                rest = rest.substringBefore('[').trim()
            }
            Item(rest, text, language)
        }
    }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                sb.append(c)
                i++
                continue
            }
            when (s[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'u' -> sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()).also { i += 4 }
                else -> sb.append(s[i + 1])
            }
            i += 2
        }
        return sb.toString()
    }

    private companion object {
        val LANGUAGES = listOf("ru", "uk", "en")
        const val MIN_CASES_PER_LANGUAGE = 150
    }
}
