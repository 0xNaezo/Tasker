package app.tasker.core.parser

import app.tasker.core.model.Bucket
import java.time.DayOfWeek

internal enum class OffsetUnit { DAYS, WEEKS, MONTHS }

/** Part of the day after an hour: "в 6 вечера" is 18:00. */
internal enum class DayPeriod { MORNING, AFTERNOON, EVENING, NIGHT }

/**
 * A language pack (tech plan §9.2, decision 5): dictionaries only, the engine is shared. Words are written naturally and
 * compared through [Keys.of], so case, "ё"/"е" and apostrophe variants (' ’ ʼ) do not matter. Space-separated strings are
 * word lists; multi-word phrases live in prefix trees.
 *
 * @param code language code reported in [ParsedField.language]; `null` for the language-neutral pack.
 * @param distinctiveLetters letters that occur only in this language; they decide which pack wins a tie.
 * @param relativeDays phrase → day offset; offset 0 is a "today" word, a horizon unless it belongs to a deadline (rule 8).
 * @param contextWeekdays weekday words that are also common words ("sat", "sun"): they need a preposition, a modifier or a
 *   deadline marker.
 * @param abbreviations words that may carry a glued dot ("пт.", "окт.", "Oct.").
 * @param datePrepositions preposition → whether it may also precede relative days and "next week" ("на завтра", but not
 *   "в завтра"); all of them may precede weekdays and dates.
 * @param months word → month number, in the forms used after a day number ("12 октября").
 * @param monthBeforeDay dates like "Oct 12" are allowed.
 * @param dayMonthJoiners words between a day and a month ("12th of October").
 * @param ordinalSuffixes suffixes glued to a day number ("12th") or after a hyphen ("12-го").
 * @param nextWeek phrases meaning "next week" without the preposition.
 * @param horizons phrases mapped to a bucket.
 * @param markers deadline markers that are prepositions; [nounMarkers] may be followed by a colon ("deadline:").
 * @param bareHourMarkers markers after which a bare number is an hour ("к 18"); after other markers an hour needs minutes,
 *   am/pm or a word such as "часов", so "дойти до 5 этажа" stays text.
 * @param timePrepositions prepositions that turn a bare number into an hour ("в 18", "о 18", "at 6").
 * @param countNouns words after a number that make it a quantity, not an hour ("до 5 штук").
 * @param alreadyDone CAP-8 prefixes with an optional colon; [alreadyDoneWithColon] require it ("done:").
 * @param rangeStarts words that open a range: "с 10 до 12" has no deadline.
 * @param danglingWords prepositions dropped from the edges of the title when they touch an extracted date or link.
 */
internal class LanguagePack(
    val code: String?,
    val distinctiveLetters: String = "",
    relativeDays: Map<String, Int> = emptyMap(),
    weekdays: Map<String, DayOfWeek> = emptyMap(),
    contextWeekdays: String = "",
    abbreviations: String = "",
    nextModifiers: String = "",
    thisModifiers: String = "",
    datePrepositions: Map<String, Boolean> = emptyMap(),
    months: Map<String, Int> = emptyMap(),
    val monthBeforeDay: Boolean = false,
    dayMonthJoiners: String = "",
    ordinalSuffixes: String = "",
    yearWords: String = "",
    offsetPrepositions: String = "",
    offsetUnits: Map<String, OffsetUnit> = emptyMap(),
    numberWords: Map<String, Int> = emptyMap(),
    nextWeek: List<String> = emptyList(),
    horizons: Map<String, Bucket> = emptyMap(),
    markers: List<String> = emptyList(),
    nounMarkers: List<String> = emptyList(),
    bareHourMarkers: String = "",
    timePrepositions: String = "",
    dayPeriods: Map<String, DayPeriod> = emptyMap(),
    hourWords: String = "",
    countNouns: String = "",
    alreadyDone: List<String> = emptyList(),
    alreadyDoneWithColon: List<String> = emptyList(),
    rangeStarts: String = "",
    danglingWords: String = "",
) {
    val relativeDayTrie: PhraseTrie<Int> = PhraseTrie.of(relativeDays)
    val weekdayByKey: Map<String, DayOfWeek> = weekdays.keyed()
    val contextWeekdayKeys: Set<String> = contextWeekdays.keySet()
    val abbreviationKeys: Set<String> = abbreviations.keySet()
    val nextModifierKeys: Set<String> = nextModifiers.keySet()
    val thisModifierKeys: Set<String> = thisModifiers.keySet()
    val datePrepositionKeys: Map<String, Boolean> = datePrepositions.keyed()
    val monthByKey: Map<String, Int> = months.keyed()
    val dayMonthJoinerKeys: Set<String> = dayMonthJoiners.keySet()
    val ordinalSuffixKeys: Set<String> = ordinalSuffixes.keySet()
    val yearWordKeys: Set<String> = yearWords.keySet()
    val offsetPrepositionKeys: Set<String> = offsetPrepositions.keySet()
    val offsetUnitByKey: Map<String, OffsetUnit> = offsetUnits.keyed()
    val numberWordByKey: Map<String, Int> = numberWords.keyed()
    val nextWeekTrie: PhraseTrie<Unit> = PhraseTrie.of(nextWeek.associateWith { })
    val horizonTrie: PhraseTrie<Bucket> = PhraseTrie.of(horizons)

    /** Marker phrase → whether it is a noun that may take a colon. */
    val markerTrie: PhraseTrie<Boolean> = PhraseTrie.of(markers.associateWith { false } + nounMarkers.associateWith { true })
    val bareHourMarkerKeys: Set<String> = bareHourMarkers.keySet()
    val timePrepositionKeys: Set<String> = timePrepositions.keySet()
    val dayPeriodByKey: Map<String, DayPeriod> = dayPeriods.keyed()
    val hourWordKeys: Set<String> = hourWords.keySet()
    val countNounKeys: Set<String> = countNouns.keySet()

    /** Prefix phrase → whether a colon is required after it. */
    val alreadyDoneTrie: PhraseTrie<Boolean> =
        PhraseTrie.of(alreadyDone.associateWith { false } + alreadyDoneWithColon.associateWith { true })
    val rangeStartKeys: Set<String> = rangeStarts.keySet()
    val danglingKeys: Set<String> = danglingWords.keySet()

    /** Content words of the pack, prepositions excluded: a word known to only one enabled pack hints at the input language. */
    val vocabulary: Set<String> = buildSet {
        addAll(weekdayByKey.keys)
        addAll(monthByKey.keys)
        addAll(nextModifierKeys)
        addAll(thisModifierKeys)
        addAll(offsetUnitByKey.keys)
        addAll(numberWordByKey.keys)
        addAll(dayPeriodByKey.keys)
        addAll(hourWordKeys)
        val phrases = relativeDays.keys + nextWeek + horizons.keys + nounMarkers + alreadyDone + alreadyDoneWithColon
        for (phrase in phrases) addAll(phrase.keySet())
    }

    private companion object {
        fun String.keySet(): Set<String> = split(' ').filter { it.isNotEmpty() }.mapTo(HashSet()) { Keys.of(it) }

        fun <V> Map<String, V>.keyed(): Map<String, V> = entries.associate { Keys.of(it.key) to it.value }
    }
}

/** Builds a word → value map from groups of space-separated forms: `forms(1 to "января янв")`. */
internal fun <V> forms(vararg groups: Pair<V, String>): Map<String, V> = buildMap {
    for ((value, words) in groups) {
        for (word in words.split(' ')) if (word.isNotEmpty()) put(word, value)
    }
}

/** Every "adjective noun" combination: `combine("следующей следующую", "неделе неделю")`. */
internal fun combine(first: String, second: String): List<String> =
    first.split(' ').filter { it.isNotEmpty() }.flatMap { a -> second.split(' ').filter { it.isNotEmpty() }.map { b -> "$a $b" } }
