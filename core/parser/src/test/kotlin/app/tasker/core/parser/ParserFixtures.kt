package app.tasker.core.parser

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** The reference moment of tech plan §9.4: Tuesday 06.10.2026, 10:00. */
object ParserFixtures {
    val TODAY: LocalDate = LocalDate.of(2026, 10, 6)
    val NOW: LocalDateTime = LocalDateTime.of(TODAY, LocalTime.of(10, 0))
    val KNOWN_PROJECTS: Set<String> = setOf("client", "work", "Работа", "дом", "Робота")

    val parser = InputParser()

    fun context(
        literalRanges: List<IntRange> = emptyList(),
        slashDateOrder: SlashDateOrder = SlashDateOrder.DAY_MONTH,
        now: LocalDateTime = NOW,
        today: LocalDate = TODAY,
        knownProjects: Set<String> = KNOWN_PROJECTS,
    ) = ParseContext(
        now = now,
        today = today,
        slashDateOrder = slashDateOrder,
        literalRanges = literalRanges,
        knownProjects = knownProjects,
    )

    fun parse(input: String, context: ParseContext = context()): ParseResult = parser.parse(input, context)

    /** Range of the first occurrence of [fragment] in [input]. */
    fun rangeOf(input: String, fragment: String): IntRange {
        val start = input.indexOf(fragment)
        require(start >= 0) { "'$fragment' is not in '$input'" }
        return start until start + fragment.length
    }
}
