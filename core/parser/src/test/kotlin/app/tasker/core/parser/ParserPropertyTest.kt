package app.tasker.core.parser

import app.tasker.core.parser.ParseInvariants.overlaps
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.arabic
import io.kotest.property.arbitrary.ascii
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.codepoints
import io.kotest.property.arbitrary.cyrillic
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.hebrew
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.text.Normalizer
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Property tests of tech plan §9.7: the parser never fails and its result is always well-formed. */
class ParserPropertyTest {
    private val parser = InputParser()
    private val context = ParserFixtures.context()

    /** Fragments that exercise every rule, plus emoji, RTL text, control and combining characters. */
    private val vocabulary = listOf(
        "завтра", "Сегодня", "послезавтра", "пт", "Пт.", "в пятницу", "до пятницы", "к пятнице", "в следующий пт", "до", "к",
        "в", "на", "о", "у", "18:00", "в 18", "к 18", "о 18", "18.00", "24:00", "6 вечера", "12.10", "12.10.2026", "12/10",
        "2026-10-12", "29.02", "31.04", "12 октября", "12 жовтня", "Oct 12", "12th of October", "через 3 дня", "через неделю",
        "на следующей неделе", "наступного тижня", "next week", "in 2 weeks", "in a week", "на неделе", "когда-нибудь",
        "колись", "someday", "this week", "сьогодні", "today", "tomorrow", "fri", "on fri", "next fri", "5pm", "at 6",
        "by", "due", "deadline:", "дедлайн", "срок:", "термін", "у п'ятницю", "п’ятниці", "уже сделал", "сделано:", "done:",
        "already did", "вже зробив", "#client", "#новый-проект", "#123", "@tag", "@учёба", "M", "S", "L", "М", "M&M",
        "S-class", "C#", "с 10 до 12", "дойти до магазина", "к врачу", "до 5 штук", "1.5", "1/2", "+7 999 123-45-67",
        "https://example.com/2026/10/12/", "www.example.com", "github.com/a/b", "ivan.12.10@mail.ru", "(", ")", "«", "»",
        ".", ",", ":", ";", "!", "?", "—", "-", "…", "😀", "👨\u200D👩\u200D👧", "שלום", "مرحبا", "\u0000", "\u0007", "\u200B", "\uFEFF",
        "\u0301", "и\u0306", "\uD800", "\n", "\t", "\r\n", "  ", "сдать отчёт", "send invoice", "оплатити рахунок",
        "каждый", "после", "every", "after", "3 часов дня", "к 10 часам утра", "в среду на следующей неделе",
        "наступного тижня у середу", "wednesday next week", "Задача L.",
    )
    private val separators = listOf(" ", " ", " ", "", "  ", "\u00A0", "\u2009", "\n", "\t")

    private val soup: Arb<String> = Arb.list(Arb.bind(Arb.element(vocabulary), Arb.element(separators)) { w, s -> w + s }, 0..24)
        .map { it.joinToString("") }

    private val inputs: Arb<String> = Arb.choice(
        soup,
        soup,
        Arb.string(0..120, Arb.codepoints()),
        Arb.string(0..80, Codepoint.cyrillic()),
        Arb.string(0..80, Codepoint.ascii()),
        Arb.string(0..40, Codepoint.hebrew()),
        Arb.string(0..40, Codepoint.arabic()),
    )

    @Test
    fun neverThrowsAndAlwaysReturnsAWellFormedResult() = runBlocking<Unit> {
        checkAll(config(3000), inputs) { input ->
            val result = parser.parse(input, context)
            assertWithMessage("input «%s»", input).that(ParseInvariants.problems(result)).isEmpty()
            assertThat(result.input).isSameInstanceAs(input)
        }
    }

    @Test
    fun parsingIsDeterministic() = runBlocking<Unit> {
        checkAll(config(500), inputs) { input ->
            val first = parser.parse(input, context)
            assertThat(parser.parse(input, context)).isEqualTo(first)
            assertThat(InputParser().parse(input, context.copy())).isEqualTo(first)
        }
    }

    @Test
    fun marksAFieldLiteralRemovesItAndReturnsItsTextToTheTitle() = runBlocking<Unit> {
        checkAll(config(1500), soup) { input ->
            val result = parser.parse(input, context)
            for (field in result.fields) {
                val again = parser.parse(input, context.copy(literalRanges = field.ranges))
                val clash = again.fields.filter { other -> other.ranges.any { r -> field.ranges.any { it.overlaps(r) } } }
                assertWithMessage("«%s» without %s", input, field.value).that(clash).isEmpty()
                assertWithMessage("«%s» without %s", input, field.value).that(ParseInvariants.problems(again)).isEmpty()
                val pieces = field.ranges.flatMap { words(Normalizer.normalize(input.substring(it), Normalizer.Form.NFC)) }
                for (piece in pieces) {
                    assertWithMessage("title of «%s» without %s", input, field.value).that(again.title).contains(piece)
                }
            }
            for (link in result.links) {
                val again = parser.parse(input, context.copy(literalRanges = listOf(link.range)))
                assertWithMessage("«%s» without %s", input, link.url).that(again.links.map { it.range }).doesNotContain(link.range)
            }
        }
    }

    @Test
    fun hugeAndRepetitiveInputsStayLinear() {
        val chunk = "пт до 18:00 сдать отчёт #client M @tag https://example.com/2026/10/12 через 3 дня в 18 с 10 до 12 😀 "
        val inputs = listOf(
            chunk.repeat(2_000),
            "до ".repeat(50_000),
            "1".repeat(100_000),
            "1.".repeat(50_000),
            "#".repeat(100_000),
            "@a".repeat(50_000),
            "a@".repeat(50_000),
            "www.".repeat(25_000),
            "http://".repeat(15_000),
            "(".repeat(50_000) + "https://x.com/" + ")".repeat(50_000),
            "\u0301".repeat(100_000),
            "и\u0306".repeat(50_000),
            " \n\t".repeat(30_000),
        )
        val started = System.nanoTime()
        for (input in inputs) {
            val result = parser.parse(input, context)
            assertWithMessage("huge input of %s chars", input.length).that(ParseInvariants.problems(result)).isEmpty()
        }
        val seconds = (System.nanoTime() - started) / 1e9
        assertWithMessage("seconds to parse %s huge inputs", inputs.size).that(seconds).isLessThan(HUGE_INPUT_BUDGET_SECONDS)
    }

    private fun config(iterations: Int) = PropTestConfig(seed = SEED, iterations = iterations)

    /** Pieces of [text] between whitespace runs, with the same notion of whitespace as the parser. */
    private fun words(text: String): List<String> = buildList {
        val current = StringBuilder()
        for (c in text) {
            if (c.isWhitespace()) {
                if (current.isNotEmpty()) add(current.toString())
                current.clear()
            } else {
                current.append(c)
            }
        }
        if (current.isNotEmpty()) add(current.toString())
    }

    private companion object {
        const val SEED = 20_261_006L
        const val HUGE_INPUT_BUDGET_SECONDS = 20.0
    }
}
