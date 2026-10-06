package app.tasker.core.parser

import app.tasker.core.model.Bucket
import java.time.LocalDate
import java.time.LocalTime

internal enum class AtomKind { DATE, TIME, MARKER, HORIZON }

/**
 * A recognized date, time, deadline marker or horizon phrase covering tokens `[first, last]`, prepositions included.
 *
 * @property todayWord the date is "сегодня"/"сьогодні"/"today": a horizon unless a deadline takes it (rule 8).
 * @property bareHours a marker after which a bare number is an hour ("к 18").
 */
internal class Atom(
    val kind: AtomKind,
    val first: Int,
    val last: Int,
    val language: String?,
    val date: LocalDate? = null,
    val todayWord: Boolean = false,
    val time: LocalTime? = null,
    val bucket: Bucket? = null,
    val bareHours: Boolean = false,
)

/**
 * Finds atoms left to right, taking the longest match over all packs at each position (tech plan §9.1, step 6). Ties go
 * to the earlier pack, so the order of [packs] decides the reported language of words shared by ru and uk.
 */
internal class TemporalScanner(
    private val packs: List<LanguagePack>,
    private val tokens: List<Token>,
    private val usable: BooleanArray,
    private val context: ParseContext,
) : TokenKeys {
    private val calendar = CalendarMath(context.today)

    private class DateCore(val last: Int, val date: LocalDate, val todayWord: Boolean = false, val specific: Boolean = true)

    private class TimeCore(val last: Int, val time: LocalTime, val specific: Boolean)

    override fun keyAt(index: Int): String? = token(index)?.key

    override fun continues(index: Int): Boolean = index < tokens.size && !tokens[index].breakBefore

    fun scan(): List<Atom> {
        val atoms = ArrayList<Atom>()
        var i = 0
        while (i < tokens.size) {
            val previous = atoms.lastOrNull()
            val marker = previous?.takeIf { it.kind == AtomKind.MARKER && it.last == i - 1 && continues(i) }
            val atom = if (usable[i]) bestAt(i, marker) else null
            if (atom == null) {
                i++
            } else {
                atoms += atom
                i = atom.last + 1
            }
        }
        return atoms
    }

    /** [marker] is the deadline marker right before position [i], if any. */
    private fun bestAt(i: Int, marker: Atom?): Atom? {
        var best: Atom? = null
        for (pack in packs) {
            best = longer(best, date(pack, i, afterMarker = marker != null))
            best = longer(best, time(pack, i, marker))
            best = longer(best, horizon(pack, i))
            best = longer(best, marker(pack, i))
        }
        return best
    }

    private fun longer(current: Atom?, candidate: Atom?): Atom? =
        if (candidate != null && (current == null || candidate.last > current.last)) candidate else current

    /** A usable token at [index]. */
    private fun token(index: Int): Token? = if (index in tokens.indices && usable[index]) tokens[index] else null

    /** A usable token at [index] that continues the expression before it. */
    private fun next(index: Int): Token? = token(index)?.takeIf { !it.breakBefore }

    private fun word(index: Int, continuation: Boolean): Token? =
        (if (continuation) next(index) else token(index))?.takeIf { it.kind == TokenKind.WORD }

    // ---- dates ----

    private fun date(pack: LanguagePack, i: Int, afterMarker: Boolean): Atom? {
        val head = token(i) ?: return null
        val prepositionAllowsRelative = if (head.kind == TokenKind.WORD) pack.datePrepositionKeys[head.key] else null
        val hasPreposition = prepositionAllowsRelative != null
        val start = if (hasPreposition) i + 1 else i
        if (hasPreposition && next(start) == null) return null
        val core = dateCore(
            pack = pack,
            j = start,
            continuation = hasPreposition,
            allowRelative = !hasPreposition || prepositionAllowsRelative == true,
            hasContext = hasPreposition || afterMarker,
        ) ?: return null
        val language = if (hasPreposition || core.specific) pack.code else null
        return Atom(AtomKind.DATE, i, core.last, language, date = core.date, todayWord = core.todayWord)
    }

    private fun dateCore(pack: LanguagePack, j: Int, continuation: Boolean, allowRelative: Boolean, hasContext: Boolean): DateCore? {
        var best: DateCore? = null
        fun offer(candidate: DateCore?) {
            if (candidate != null && (best == null || candidate.last > (best?.last ?: -1))) best = candidate
        }
        if (allowRelative) {
            offer(relativeDay(pack, j))
            offer(nextWeek(pack, j))
        }
        offer(weekday(pack, j, continuation, hasContext))
        offer(dayMonth(pack, j))
        offer(monthDay(pack, j, continuation))
        offer(numericDate(j))
        if (!continuation) offer(offset(pack, j))
        return best
    }

    private fun relativeDay(pack: LanguagePack, j: Int): DateCore? {
        val match = pack.relativeDayTrie.match(j, this) ?: return null
        return DateCore(j + match.length - 1, calendar.plusDays(match.value), todayWord = match.value == 0)
    }

    private fun nextWeek(pack: LanguagePack, j: Int): DateCore? {
        val match = pack.nextWeekTrie.match(j, this) ?: return null
        return DateCore(j + match.length - 1, calendar.mondayOfNextWeek())
    }

    private fun weekday(pack: LanguagePack, j: Int, continuation: Boolean, hasContext: Boolean): DateCore? {
        val head = word(j, continuation) ?: return null
        val next = head.key in pack.nextModifierKeys
        val modified = next || head.key in pack.thisModifierKeys
        val dayIndex = if (modified) abbreviationEnd(pack, j) + 1 else j
        val dayWord = if (modified) word(dayIndex, continuation = true) else head
        val day = dayWord?.let { pack.weekdayByKey[it.key] } ?: return null
        if (!modified && !hasContext && head.key in pack.contextWeekdayKeys) return null
        val date = if (next) calendar.weekdayOfNextWeek(day) else calendar.nearestWeekday(day)
        return DateCore(abbreviationEnd(pack, dayIndex), date)
    }

    /** "12 октября", "12 Oct 2026", "12th of October", "12-го жовтня". */
    private fun dayMonth(pack: LanguagePack, j: Int): DateCore? {
        if (pack.monthByKey.isEmpty()) return null
        val number = token(j)?.takeIf { it.isPlainNumber && it.text.length <= 2 } ?: return null
        var k = ordinalEnd(pack, j)
        var monthWord = word(k + 1, continuation = true) ?: return null
        if (monthWord.key in pack.dayMonthJoinerKeys) {
            k++
            monthWord = word(k + 1, continuation = true) ?: return null
        }
        val month = pack.monthByKey[monthWord.key] ?: return null
        return explicitDate(pack, number.text.toInt(), month, abbreviationEnd(pack, k + 1))
    }

    /** "Oct 12", "October 12th, 2026". */
    private fun monthDay(pack: LanguagePack, j: Int, continuation: Boolean): DateCore? {
        if (!pack.monthBeforeDay) return null
        val monthWord = word(j, continuation) ?: return null
        val month = pack.monthByKey[monthWord.key] ?: return null
        val k = abbreviationEnd(pack, j) + 1
        val number = next(k)?.takeIf { it.isPlainNumber && it.text.length <= 2 } ?: return null
        return explicitDate(pack, number.text.toInt(), month, ordinalEnd(pack, k))
    }

    private fun explicitDate(pack: LanguagePack, day: Int, month: Int, last: Int): DateCore? {
        var end = last
        var k = last + 1
        if (pack.monthBeforeDay && next(k)?.isPunct(',') == true) k++
        val yearToken = next(k)?.takeIf { it.isPlainNumber && it.text.length == 4 }
        val year = yearToken?.text?.toInt()?.takeIf { it in YEARS }
        if (year != null) {
            end = k
            val yearWord = word(k + 1, continuation = true)
            if (yearWord != null && yearWord.key in pack.yearWordKeys) end = abbreviationEnd(pack, k + 1)
        }
        val date = calendar.resolve(day, month, year) ?: return null
        return DateCore(end, date)
    }

    private fun numericDate(j: Int): DateCore? {
        val number = token(j)?.takeIf { it.kind == TokenKind.NUMBER } ?: return null
        val parts = NumericFormats.date(number.text, context.slashDateOrder) ?: return null
        val date = calendar.resolve(parts.day, parts.month, parts.year) ?: return null
        return DateCore(j, date, specific = false)
    }

    /** "через 3 дня", "через неделю", "in 2 weeks", "in a week". */
    private fun offset(pack: LanguagePack, j: Int): DateCore? {
        val head = word(j, continuation = false)?.takeIf { it.key in pack.offsetPrepositionKeys } ?: return null
        val amount = next(head.index + 1) ?: return null
        val count = when {
            amount.isPlainNumber && amount.text.length <= 3 -> amount.text.toInt()
            amount.kind == TokenKind.WORD -> pack.numberWordByKey[amount.key]
            else -> null
        }
        val unitAfterCount = if (count != null) next(amount.index + 1)?.let { pack.offsetUnitByKey[it.key] } else null
        if (count != null && unitAfterCount != null) return DateCore(amount.index + 1, calendar.plus(count, unitAfterCount))
        val singleUnit = pack.offsetUnitByKey[amount.key] ?: return null
        return DateCore(amount.index, calendar.plus(1, singleUnit))
    }

    /** Index of the last token of the word at [k]: a glued dot after an abbreviation belongs to it ("пт.", "Oct."). */
    private fun abbreviationEnd(pack: LanguagePack, k: Int): Int {
        val word = tokens.getOrNull(k) ?: return k
        val dot = next(k + 1)
        val glued = dot != null && dot.isPunct('.') && !dot.spaceBefore
        return if (glued && word.key in pack.abbreviationKeys) k + 1 else k
    }

    /** Index of the last token of a day number with an ordinal suffix: "12th", "12-го". */
    private fun ordinalEnd(pack: LanguagePack, j: Int): Int {
        val suffix = next(j + 1)?.takeIf { !it.spaceBefore } ?: return j
        if (suffix.kind == TokenKind.WORD && suffix.key in pack.ordinalSuffixKeys) return j + 1
        val afterHyphen = if (suffix.isPunct('-')) next(j + 2)?.takeIf { !it.spaceBefore } else null
        return if (afterHyphen != null && afterHyphen.kind == TokenKind.WORD && afterHyphen.key in pack.ordinalSuffixKeys) j + 2 else j
    }

    // ---- times ----

    /** [marker] is the deadline marker right before position [i], if any. */
    private fun time(pack: LanguagePack, i: Int, marker: Atom?): Atom? {
        val head = token(i) ?: return null
        val hasPreposition = head.kind == TokenKind.WORD && head.key in pack.timePrepositionKeys
        val start = if (hasPreposition) i + 1 else i
        if (hasPreposition && next(start) == null) return null
        val core = timeCore(
            pack = pack,
            j = start,
            allowWords = hasPreposition || marker != null,
            allowBareHour = hasPreposition || marker?.bareHours == true,
        ) ?: return null
        val language = if (hasPreposition || core.specific) pack.code else null
        return Atom(AtomKind.TIME, i, core.last, language, time = core.time)
    }

    /**
     * A time at token [j]. A bare number is an hour only when [allowBareHour] ("в 18", "к 18"); words such as "вечера" or
     * "часов" attach when [allowWords] or the time has minutes, so "до 6 вечера" is a time but "2 часа работы" is not.
     */
    private fun timeCore(pack: LanguagePack, j: Int, allowWords: Boolean, allowBareHour: Boolean): TimeCore? {
        val number = token(j)?.takeIf { it.kind == TokenKind.NUMBER } ?: return null
        val clock = NumericFormats.clock(number.text) ?: return null
        var hour = clock.hour
        var last = j
        var strong = clock.hasMinutes
        var specific = false
        val after = next(j + 1)?.takeIf { it.kind == TokenKind.WORD }
        val takesWords = allowWords || clock.hasMinutes
        val period = if (takesWords) after?.let { pack.dayPeriodByKey[it.key] } else null
        val hourWord = after != null && (takesWords || !after.spaceBefore) && after.key in pack.hourWordKeys
        when {
            after != null && after.key in AM_PM -> {
                if (hour !in 1..12) return null
                hour = hour % 12 + if (after.key == PM) 12 else 0
                last = j + 1
                strong = true
            }
            period != null -> {
                val adjusted = withPeriod(hour, period)
                if (adjusted != null) {
                    hour = adjusted
                    last = j + 1
                    strong = true
                    specific = true
                }
            }
            hourWord -> {
                last = abbreviationEnd(pack, j + 1)
                strong = true
                specific = true
            }
        }
        if (!strong && (!allowBareHour || isQuantity(pack, j + 1))) return null
        if (hour !in 0..23) return null
        return TimeCore(last, LocalTime.of(hour, clock.minute), specific)
    }

    /** A bare number followed by a unit, a symbol or a glued suffix is a quantity: "до 5 штук", "в 2 раза", "до 5%". */
    private fun isQuantity(pack: LanguagePack, k: Int): Boolean {
        val after = tokens.getOrNull(k) ?: return false
        if (!after.spaceBefore && !(after.kind == TokenKind.PUNCT && after.text in SENTENCE_PUNCTUATION)) return true
        return when (after.kind) {
            TokenKind.WORD -> after.key in pack.countNounKeys
            TokenKind.PUNCT -> after.text in QUANTITY_SYMBOLS
            else -> false
        }
    }

    private fun withPeriod(hour: Int, period: DayPeriod): Int? = when (period) {
        DayPeriod.MORNING -> hour.takeIf { it in 0..11 }
        DayPeriod.AFTERNOON -> when (hour) {
            12 -> 12
            in 1..6 -> hour + 12
            in 13..18 -> hour
            else -> null
        }
        DayPeriod.EVENING -> when (hour) {
            in 1..11 -> hour + 12
            in 13..23 -> hour
            else -> null
        }
        DayPeriod.NIGHT -> when (hour) {
            12 -> 0
            in 0..5 -> hour
            in 9..11 -> hour + 12
            in 21..23 -> hour
            else -> null
        }
    }

    // ---- markers and horizons ----

    /** One or more deadline markers in a row ("due by", "дедлайн до"); a noun marker may take a colon. */
    private fun marker(pack: LanguagePack, i: Int): Atom? {
        var last = -1
        var j = i
        var bareHours = false
        while (j == i || next(j) != null) {
            val match = pack.markerTrie.match(j, this) ?: break
            last = j + match.length - 1
            bareHours = match.length == 1 && tokens[j].key in pack.bareHourMarkerKeys
            if (match.value && next(last + 1)?.isPunct(':') == true) last++
            j = last + 1
        }
        return if (last >= i) Atom(AtomKind.MARKER, i, last, pack.code, bareHours = bareHours) else null
    }

    private fun horizon(pack: LanguagePack, i: Int): Atom? {
        val match = pack.horizonTrie.match(i, this) ?: return null
        return Atom(AtomKind.HORIZON, i, i + match.length - 1, pack.code, bucket = match.value)
    }

    private companion object {
        val YEARS = 1900..2199
        const val PM = "pm"
        val AM_PM = setOf("am", PM)
        const val SENTENCE_PUNCTUATION = ".,;:!?)»"
        val QUANTITY_SYMBOLS = setOf("%", "$", "€", "₽", "₴", "£", "×", "*", "/")
    }
}
