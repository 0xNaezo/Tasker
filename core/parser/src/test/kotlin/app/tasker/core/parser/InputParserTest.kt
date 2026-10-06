package app.tasker.core.parser

import app.tasker.core.model.Bucket
import app.tasker.core.model.Estimate
import app.tasker.core.parser.ParserFixtures.context
import app.tasker.core.parser.ParserFixtures.parse
import app.tasker.core.parser.ParserFixtures.rangeOf
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Test

class InputParserTest {
    private fun date(month: Int, day: Int, year: Int = 2026): LocalDate = LocalDate.of(year, month, day)

    private fun deadline(month: Int, day: Int, hour: Int? = null, minute: Int = 0) =
        ParsedValue.Deadline(date(month, day), hour?.let { LocalTime.of(it, minute) })

    @Test
    fun techPlanExamples() {
        data class Expected(
            val title: String,
            val deadline: ParsedValue.Deadline? = null,
            val planDate: LocalDate? = null,
            val bucket: Bucket? = null,
            val estimate: Estimate? = null,
            val project: ParsedValue.Project? = null,
            val tags: List<String> = emptyList(),
            val alreadyDone: Boolean = false,
        )
        val client = ParsedValue.Project("client", isNew = false)
        val examples = mapOf(
            "пт до 18:00 сдать отчёт #client M" to
                Expected("сдать отчёт", deadline = deadline(10, 9, 18), project = client, estimate = Estimate.M),
            "завтра позвонить" to Expected("позвонить", planDate = date(10, 7)),
            "дойти до магазина" to Expected("дойти до магазина"),
            "к врачу в чт" to Expected("к врачу", planDate = date(10, 8)),
            "сегодня разобрать почту S" to Expected("разобрать почту", bucket = Bucket.TODAY, estimate = Estimate.S),
            "когда-нибудь выучить Rust @учёба" to Expected("выучить Rust", bucket = Bucket.SOMEDAY, tags = listOf("учёба")),
            "уже сделал ревью PR" to Expected("ревью PR", alreadyDone = true),
            "созвон завтра в 15" to Expected("созвон в 15", planDate = date(10, 7)),
            "созвон сегодня в 15" to Expected("созвон в 15", bucket = Bucket.TODAY),
            "сегодня до 18:00 отправить счёт" to Expected("отправить счёт", deadline = deadline(10, 6, 18)),
            "deadline fri 5pm send invoice" to Expected("send invoice", deadline = deadline(10, 9, 17)),
            "у п'ятницю до 12:00 оплатити рахунок" to Expected("оплатити рахунок", deadline = deadline(10, 9, 12)),
            "завтра #client M" to Expected("завтра #client M", planDate = date(10, 7), project = client, estimate = Estimate.M),
        )
        for ((input, expected) in examples) {
            val r = parse(input, context(knownProjects = setOf("client")))
            val actual = Expected(r.title, r.deadline, r.planDate, r.bucket, r.estimate, r.project, r.tags, r.alreadyDone)
            assertWithMessage("input «%s»", input).that(actual).isEqualTo(expected)
        }
    }

    @Test
    fun rangesPointIntoTheOriginalInputDespiteWhitespaceCollapsing() {
        val input = "  сдать\u00A0отчёт \n\n до\t\u00A0 пятницы  "
        val result = parse(input)
        val field = result.fields.single()
        assertThat(field.value).isEqualTo(deadline(10, 9))
        assertThat(field.ranges).containsExactly(rangeOf(input, "до\t\u00A0 пятницы"))
        assertThat(field.text(input)).isEqualTo("до\t\u00A0 пятницы")
        assertThat(result.title).isEqualTo("сдать отчёт")
    }

    @Test
    fun aLineBreakEndsThePhrase() {
        // A marker does not reach over a line break, and a time does not bind to a date on another line.
        val split = parse("сдать отчёт до\nпятницы")
        assertThat(split.deadline).isNull()
        assertThat(split.planDate).isEqualTo(date(10, 9))
        val lines = parse("в пятницу созвон\nотчёт до 18:00")
        assertThat(lines.planDate).isEqualTo(date(10, 9))
        assertThat(lines.deadline).isEqualTo(deadline(10, 6, 18))
    }

    @Test
    fun rangesPointIntoTheOriginalInputDespiteNfcComposition() {
        val input = "наи\u0306ти ключи у наи\u0306ближчии\u0306 четвер"
        val result = parse(input)
        assertThat(result.title).isEqualTo("найти ключи")
        assertThat(result.fields.single().ranges).containsExactly(rangeOf(input, "у наи\u0306ближчии\u0306 четвер"))
        assertThat(result.planDate).isEqualTo(date(10, 8))
    }

    @Test
    fun deadlineFragmentsAreJoinedWithASpace() {
        val input = "в пт сдать отчёт до 18:00"
        val field = parse(input).fields.single()
        assertThat(field.ranges).containsExactly(rangeOf(input, "в пт"), rangeOf(input, "до 18:00")).inOrder()
        assertThat(field.text(input)).isEqualTo("в пт до 18:00")
        assertThat(field.language).isEqualTo("ru")
    }

    @Test
    fun removingAChipReturnsItsTextAndKeepsTheOtherFields() {
        val input = "пт до 18:00 сдать отчёт #client M"
        val chip = parse(input).fields.first { it.value is ParsedValue.Deadline }
        val again = parse(input, context(literalRanges = chip.ranges))
        assertThat(again.deadline).isNull()
        assertThat(again.planDate).isNull()
        assertThat(again.title).isEqualTo("пт до 18:00 сдать отчёт")
        assertThat(again.project).isEqualTo(ParsedValue.Project("client", isNew = false))
        assertThat(again.estimate).isEqualTo(Estimate.M)
    }

    @Test
    fun aLiteralRangeNeverSplitsAUrl() {
        val input = "читать https://example.com/2026/10/12 завтра"
        val result = parse(input, context(literalRanges = listOf(rangeOf(input, "2026/10"))))
        assertThat(result.links).isEmpty()
        assertThat(result.title).isEqualTo("читать https://example.com/2026/10/12")
        assertThat(result.planDate).isEqualTo(date(10, 7))
    }

    @Test
    fun invalidLiteralRangesAreIgnored() {
        val input = "завтра позвонить"
        val result = parse(input, context(literalRanges = listOf(5..2, -10..-1, 100..200)))
        assertThat(result.planDate).isEqualTo(date(10, 7))
    }

    @Test
    fun urlsLeaveTheTitleAndEmailsStay() {
        val input = "написать ivan@mail.ru про www.example.com/a"
        val result = parse(input)
        assertThat(result.links).containsExactly(
            ParsedLink("mailto:ivan@mail.ru", rangeOf(input, "ivan@mail.ru"), LinkKind.EMAIL),
            ParsedLink("https://www.example.com/a", rangeOf(input, "www.example.com/a"), LinkKind.URL),
        ).inOrder()
        assertThat(result.title).isEqualTo("написать ivan@mail.ru про")
    }

    @Test
    fun urlOnlyInputGetsAShortTitle() {
        assertThat(parse("https://www.github.com/org/repo/?tab=readme#top").title).isEqualTo("github.com/org/repo")
        assertThat(parse("http://example.com").title).isEqualTo("example.com")
        assertThat(parse("завтра https://example.com/a/b/").title).isEqualTo("example.com/a/b")
    }

    @Test
    fun datesInsideUrlsAndEmailsAreNotParsed() {
        val result = parse("https://example.com/2026/10/12/ и ivan.12.10@mail.ru")
        assertThat(result.fields).isEmpty()
        assertThat(result.links.map { it.kind }).containsExactly(LinkKind.URL, LinkKind.EMAIL).inOrder()
    }

    @Test
    fun knownProjectsMatchCaseInsensitivelyAndKeepTheirSpelling() {
        assertThat(parse("#CLIENT созвон", context(knownProjects = setOf("Client"))).project)
            .isEqualTo(ParsedValue.Project("Client", isNew = false))
        assertThat(parse("#учеба план", context(knownProjects = setOf("Учёба"))).project)
            .isEqualTo(ParsedValue.Project("Учёба", isNew = false))
        assertThat(parse("#мой_проект план", context(knownProjects = setOf("Мой проект"))).project)
            .isEqualTo(ParsedValue.Project("Мой проект", isNew = false))
        assertThat(parse("#новый план", context(knownProjects = setOf("Client"))).project)
            .isEqualTo(ParsedValue.Project("новый", isNew = true))
    }

    @Test
    fun onlyTheFirstProjectIsTakenAndTagsAreDeduplicated() {
        val result = parse("@work @home @Work задача #a #b")
        assertThat(result.tags).containsExactly("work", "home").inOrder()
        assertThat(result.project).isEqualTo(ParsedValue.Project("a", isNew = true))
        assertThat(result.title).isEqualTo("задача #b")
    }

    @Test
    fun estimateIsAStandaloneUppercaseLetter() {
        assertThat(parse("задача S").estimate).isEqualTo(Estimate.S)
        assertThat(parse("задача М").estimate).isEqualTo(Estimate.M)
        assertThat(parse("задача l").estimate).isNull()
        assertThat(parse("S-class").estimate).isNull()
        assertThat(parse("M&M").estimate).isNull()
        assertThat(parse("М. Горький").estimate).isNull()
    }

    @Test
    fun onlyEnabledLanguagesAreParsed() {
        val context = context()
        val english = InputParser(setOf("en"))
        val result = english.parse("завтра call mom tomorrow", context)
        assertThat(result.planDate).isEqualTo(date(10, 7))
        assertThat(result.title).isEqualTo("завтра call mom")

        val neutral = InputParser(emptySet()).parse("завтра 12.10 в 18:00 #client M", context)
        assertThat(neutral.planDate).isEqualTo(date(10, 12))
        assertThat(neutral.project).isEqualTo(ParsedValue.Project("client", isNew = false))
        assertThat(neutral.estimate).isEqualTo(Estimate.M)
        assertThat(neutral.title).isEqualTo("завтра в 18:00")

        assertThat(InputParser(setOf("de", "en")).parse("tomorrow call", context).planDate).isEqualTo(date(10, 7))
    }

    @Test
    fun sharedRussianAndUkrainianWordsFollowTheLanguageOfTheInput() {
        assertThat(parse("завтра позвонить").fields.single().language).isEqualTo("ru")
        assertThat(parse("завтра зателефонувати мамі").fields.single().language).isEqualTo("uk")
        assertThat(parse("до п'ятниці звіт").fields.single().language).isEqualTo("uk")
        assertThat(parse("12.10 позвонить").fields.single().language).isNull()
    }

    @Test
    fun aTimeWithoutADateIsItsNextOccurrence() {
        assertThat(parse("до 9:00 отчёт").deadline).isEqualTo(deadline(10, 7, 9))
        assertThat(parse("до 10:00 отчёт").deadline).isEqualTo(deadline(10, 6, 10))
        assertThat(parse("до 18:00 отчёт").deadline).isEqualTo(deadline(10, 6, 18))
        // 01:00 after midnight: the logical day is still the 6th, but "до 03:00" means the coming 03:00.
        val night = context(now = LocalDateTime.of(2026, 10, 7, 1, 0), today = date(10, 6))
        assertThat(parse("до 03:00 доделать", night).deadline).isEqualTo(deadline(10, 7, 3))
        assertThat(parse("завтра позвонить", night).planDate).isEqualTo(date(10, 7))
    }

    @Test
    fun weekdaysAndDatesAreResolvedFromTheLogicalToday() {
        val sunday = context(now = LocalDateTime.of(2026, 10, 11, 12, 0), today = date(10, 11))
        assertThat(parse("в пятницу", sunday).planDate).isEqualTo(date(10, 16))
        assertThat(parse("в воскресенье", sunday).planDate).isEqualTo(date(10, 11))
        assertThat(parse("в следующий понедельник", sunday).planDate).isEqualTo(date(10, 12))
        assertThat(parse("на следующей неделе", sunday).planDate).isEqualTo(date(10, 12))
        assertThat(parse("11.10", sunday).planDate).isEqualTo(date(10, 11))
        assertThat(parse("10.10", sunday).planDate).isEqualTo(date(10, 10, 2027))
    }

    @Test
    fun blankInputGivesAnEmptyResult() {
        for (input in listOf("", "   ", " \n\t ")) {
            val result = parse(input)
            assertThat(result.title).isEmpty()
            assertThat(result.fields).isEmpty()
            assertThat(result.links).isEmpty()
            assertThat(result.input).isEqualTo(input)
        }
    }

    @Test
    fun inputWithoutWordsKeepsItsText() {
        assertThat(parse("!!!").title).isEqualTo("!!!")
        assertThat(parse("\u200B").title).isEqualTo("\u200B")
        assertThat(parse("😀").title).isEqualTo("😀")
    }

    @Test
    fun computedPropertiesReadTheFields() {
        val result = parse("уже сделал до пятницы 18:00 отчёт #client @a @b L")
        assertThat(result.alreadyDone).isTrue()
        assertThat(result.deadline).isEqualTo(deadline(10, 9, 18))
        assertThat(result.project).isEqualTo(ParsedValue.Project("client", isNew = false))
        assertThat(result.tags).containsExactly("a", "b").inOrder()
        assertThat(result.estimate).isEqualTo(Estimate.L)
        assertThat(result.planDate).isNull()
        assertThat(result.bucket).isNull()
        assertThat(result.title).isEqualTo("отчёт")

        val week = parse("на этой неделе через 3 дня")
        assertThat(week.bucket).isEqualTo(Bucket.WEEK)
        assertThat(week.planDate).isEqualTo(date(10, 9))
    }

    @Test
    fun fieldsAreOrderedByPosition() {
        val result = parse("M #client завтра @tag")
        assertThat(result.fields.map { it.value::class }).containsExactly(
            ParsedValue.Size::class,
            ParsedValue.Project::class,
            ParsedValue.PlanDate::class,
            ParsedValue.Tag::class,
        ).inOrder()
    }

    @Test
    fun aParserInstanceIsReusableAndStateless() {
        val parser = InputParser()
        val first = parser.parse("пт до 18:00 сдать отчёт", context())
        parser.parse("совсем другая строка 12.10 #x", context(slashDateOrder = SlashDateOrder.MONTH_DAY))
        assertThat(parser.parse("пт до 18:00 сдать отчёт", context())).isEqualTo(first)
    }
}
