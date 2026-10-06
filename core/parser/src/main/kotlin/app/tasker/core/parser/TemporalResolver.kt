package app.tasker.core.parser

import app.tasker.core.model.Bucket
import java.time.LocalDate
import java.time.LocalTime

/** A temporal field and the atoms it was built from. */
internal class TemporalField(val value: ParsedValue, val atoms: List<Atom>, val language: String?)

internal class TemporalFields(val deadline: TemporalField?, val planDate: TemporalField?, val horizon: TemporalField?)

/**
 * Turns atoms into the deadline, plan date and horizon (tech plan §9.1, steps 7–8) following the rules of §9.3:
 * - a marker counts only when a date or time follows it right away (rule 1);
 * - a marker with a time takes the nearest date of the same phrase (rule 2), otherwise the next occurrence of that time
 *   (rule 3);
 * - a date without a marker is the plan date (rule 7), "today" without a marker is the Today bucket (rule 8);
 * - of several candidates of one kind the first wins and the rest stay in the text (rule 9).
 */
internal class TemporalResolver(
    private val tokens: List<Token>,
    private val atoms: List<Atom>,
    private val packs: List<LanguagePack>,
    private val context: ParseContext,
) {
    private class DeadlineExpression(val markers: MutableList<Atom>, var date: Atom?, var time: Atom?) {
        val start: Int get() = atoms().first().first
        val end: Int get() = atoms().last().last

        fun atoms(): List<Atom> = (markers + listOfNotNull(date, time)).sortedBy { it.first }
    }

    private val phraseOf: IntArray = phraseIds()

    /** Atoms of a time or date range ("с 10 до 12"): never fields. */
    private val suppressed = HashSet<Atom>()

    fun resolve(): TemporalFields {
        val expressions = deadlineExpressions()
        val primary = expressions.firstOrNull()
        if (primary != null) bind(primary, expressions)
        val taken = expressions.flatMapTo(HashSet()) { it.atoms() }
        val free = atoms.filter { it !in taken && it !in suppressed }
        val plan = free.firstOrNull { it.kind == AtomKind.DATE && !it.todayWord }
        val horizon = free.firstOrNull { it.kind == AtomKind.HORIZON || (it.kind == AtomKind.DATE && it.todayWord) }
        return TemporalFields(
            deadline = primary?.let(::deadlineField),
            planDate = plan?.date?.let { TemporalField(ParsedValue.PlanDate(it), listOf(plan), plan.language) },
            horizon = horizon?.let { TemporalField(ParsedValue.Horizon(it.bucket ?: Bucket.TODAY), listOf(it), it.language) },
        )
    }

    private fun deadlineExpressions(): MutableList<DeadlineExpression> {
        val result = ArrayList<DeadlineExpression>()
        var k = 0
        while (k < atoms.size) {
            val marker = atoms[k]
            val expression = if (marker.kind == AtomKind.MARKER && !isRangeEnd(k)) expressionAt(k) else null
            if (expression == null) {
                k++
            } else {
                result += expression
                k += expression.atoms().size
            }
        }
        return result
    }

    /** MARKER DATE [TIME] or MARKER TIME [DATE]: "до пятницы 18:00", "до 18:00 пятницы", "к 18". */
    private fun expressionAt(k: Int): DeadlineExpression? {
        val marker = atoms[k]
        val first = adjacentAfter(k) ?: return null
        val second = adjacentAfter(k + 1)
        return when (first.kind) {
            AtomKind.DATE -> DeadlineExpression(mutableListOf(marker), first, second?.takeIf { it.kind == AtomKind.TIME })
            AtomKind.TIME -> DeadlineExpression(mutableListOf(marker), second?.takeIf { it.kind == AtomKind.DATE }, first)
            else -> null
        }
    }

    /** The atom right after `atoms[k]`, with no token or line break between them. */
    private fun adjacentAfter(k: Int): Atom? {
        val current = atoms.getOrNull(k) ?: return null
        val following = atoms.getOrNull(k + 1) ?: return null
        return following.takeIf { it.first == current.last + 1 && !tokens[it.first].breakBefore }
    }

    /** "с 10 до 12", "з 10:00 до 12:00", "с понедельника до пятницы": a range, not a deadline; both ends stay text. */
    private fun isRangeEnd(k: Int): Boolean {
        val marker = atoms[k]
        val before = atoms.getOrNull(k - 1)
            ?.takeIf { it.last == marker.first - 1 && (it.kind == AtomKind.TIME || it.kind == AtomKind.DATE) }
        val rangeStart = before?.first
            ?: tokens.getOrNull(marker.first - 1)?.takeIf { it.isPlainNumber && !it.literal }?.index
            ?: return false
        val opener = tokens.getOrNull(rangeStart - 1) ?: return false
        val isRange = opener.kind == TokenKind.WORD && !opener.literal && packs.any { opener.key in it.rangeStartKeys }
        if (isRange) {
            before?.let { suppressed += it }
            adjacentAfter(k)?.takeIf { it.kind == AtomKind.TIME || it.kind == AtomKind.DATE }?.let { suppressed += it }
        }
        return isRange
    }

    private fun bind(primary: DeadlineExpression, expressions: MutableList<DeadlineExpression>) {
        if (primary.date == null) {
            bindNearestDate(primary, expressions)
            return
        }
        if (primary.time != null) return
        // "до пятницы до 18:00": a later marker with a time in the same phrase completes the deadline.
        val other = expressions.drop(1).firstOrNull { it.date == null && samePhrase(it.start, primary.start) } ?: return
        primary.time = other.time
        primary.markers += other.markers
        expressions.remove(other)
    }

    /** Rule 2: the nearest date of the same phrase, before or after; a tie goes to the earlier one. */
    private fun bindNearestDate(primary: DeadlineExpression, expressions: MutableList<DeadlineExpression>) {
        val taken = expressions.flatMapTo(HashSet()) { it.atoms() }
        var bestDistance = Int.MAX_VALUE
        var bestStart = Int.MAX_VALUE
        var bestAtom: Atom? = null
        var bestExpression: DeadlineExpression? = null
        fun offer(start: Int, end: Int, atom: Atom?, expression: DeadlineExpression?) {
            if (!samePhrase(start, primary.start)) return
            val distance = if (end < primary.start) primary.start - end - 1 else start - primary.end - 1
            if (distance < bestDistance || (distance == bestDistance && start < bestStart)) {
                bestDistance = distance
                bestStart = start
                bestAtom = atom
                bestExpression = expression
            }
        }
        for (atom in atoms) {
            if (atom.kind == AtomKind.DATE && atom !in taken && atom !in suppressed) offer(atom.first, atom.last, atom, null)
        }
        for (expression in expressions) {
            if (expression !== primary && expression.time == null) offer(expression.start, expression.end, null, expression)
        }
        val expression = bestExpression
        if (expression != null) {
            primary.date = expression.date
            primary.markers += expression.markers
            expressions.remove(expression)
        } else {
            primary.date = bestAtom
        }
    }

    private fun deadlineField(expression: DeadlineExpression): TemporalField {
        val time = expression.time?.time
        val date = expression.date?.date ?: nextOccurrence(checkNotNull(time) { "A deadline has a date or a time" })
        val language = expression.markers.first().language
        return TemporalField(ParsedValue.Deadline(date, time), expression.atoms(), language)
    }

    /** Rule 3: today if the time has not passed yet, otherwise tomorrow (by the wall clock, so 01:00 + "до 03:00" is today). */
    private fun nextOccurrence(time: LocalTime): LocalDate {
        val day = context.now.toLocalDate()
        return if (time.isBefore(context.now.toLocalTime().withSecond(0).withNano(0))) day.plusDays(1) else day
    }

    private fun samePhrase(a: Int, b: Int): Boolean = phraseOf[a] == phraseOf[b]

    /** Phrases end at sentence punctuation and line breaks; a dot inside an atom ("пт.") does not count. */
    private fun phraseIds(): IntArray {
        val inAtom = BooleanArray(tokens.size)
        for (atom in atoms) for (i in atom.first..atom.last) inAtom[i] = true
        val ids = IntArray(tokens.size)
        var id = 0
        for (token in tokens) {
            if (token.breakBefore) id++
            ids[token.index] = id
            if (!inAtom[token.index] && token.kind == TokenKind.PUNCT && token.text in PHRASE_ENDS) id++
        }
        return ids
    }

    private companion object {
        val PHRASE_ENDS = setOf(".", "!", "?", ";", "…")
    }
}
