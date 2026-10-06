package app.tasker.core.ai

import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.EnrichRoute
import app.tasker.core.ai.contract.EstimateScale
import app.tasker.core.ai.contract.ExtractedFields
import app.tasker.core.ai.contract.SimilarTask
import app.tasker.core.data.ai.AiCommands
import app.tasker.core.data.ai.AiFill
import app.tasker.core.data.command.ParserProvider
import app.tasker.core.data.settings.SettingsRepository
import app.tasker.core.domain.provenance.FieldProvenance
import app.tasker.core.domain.time.DayClock
import app.tasker.core.model.AppSettings
import app.tasker.core.model.Deadline
import app.tasker.core.model.Estimate
import app.tasker.core.model.FieldSource
import app.tasker.core.model.Task
import app.tasker.core.model.TaskField
import app.tasker.core.model.TaskStatus
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject

/**
 * Builds `/v1/enrich` requests (tech plan §17.3). Only what the route lists leaves the device: the task title (never
 * the note, snapshots or history), the fields the rules or the user already set, a language hint, the current date,
 * time and zone, the user's S/M/L durations and up to five similar completed tasks picked on the device.
 */
class EnrichRequestFactory @Inject constructor(
    private val clock: DayClock,
    private val settings: SettingsRepository,
    private val parsers: ParserProvider,
    private val commands: AiCommands,
) {
    suspend fun build(task: Task): EnrichRequest {
        val current = settings.current()
        val text = clip(task.title.trim())
        return base(current, text, LanguageHint.detect(text, parsers.languages(current))).copy(
            extracted = extractedFields(task),
            similarDone = similarDone(task, text),
        )
    }

    /** The connection check: a fixed harmless text, nothing from the user's tasks. */
    suspend fun probe(): EnrichRequest = base(settings.current(), PROBE_TEXT, PROBE_LANGUAGE)

    private fun base(current: AppSettings, text: String, language: String?): EnrichRequest = EnrichRequest(
        text = text,
        language = language,
        now = clock.localNow().truncatedTo(ChronoUnit.MINUTES).toString(),
        today = clock.today().toString(),
        timeZone = clock.zone().id,
        estimateScale = with(current.estimateMinutes) { EstimateScale(s, m, l) },
        extracted = ExtractedFields(),
    )

    /**
     * Completed tasks lexically similar to the title. A task that is itself already done would find itself first, so
     * one more is requested and its own entry dropped.
     */
    private suspend fun similarDone(task: Task, text: String): List<SimilarTask> {
        var examples = commands.similarDone(text, EnrichRoute.MAX_SIMILAR_TASKS + 1)
        if (task.status == TaskStatus.DONE) {
            val own = examples.indexOfFirst { it.title == task.title }
            if (own >= 0) examples = examples.filterIndexed { index, _ -> index != own }
        }
        return examples.take(EnrichRoute.MAX_SIMILAR_TASKS).mapNotNull { example ->
            val title = clip(example.title.trim()).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SimilarTask(
                text = title,
                estimate = example.estimate?.name,
                actualMinutes = example.actualMinutes?.coerceIn(0, EnrichRoute.MAX_ACTUAL_MINUTES),
            )
        }
    }

    internal companion object {
        const val PROBE_TEXT = "Buy milk"
        const val PROBE_LANGUAGE = "en"

        /**
         * Fields the AI must not change (§8.3): set by the rules, the user or an integration. Values the AI itself may
         * overwrite (empty, AI or DEFAULT) are not "already extracted".
         */
        fun extractedFields(task: Task): ExtractedFields {
            fun kept(field: TaskField) = FieldProvenance.hasValue(task, field) && !FieldProvenance.canWrite(task, field, FieldSource.AI)
            val deadline = task.deadline?.takeIf { kept(TaskField.DEADLINE) }
            return ExtractedFields(
                estimate = task.estimate?.takeIf { kept(TaskField.ESTIMATE) }?.name,
                deadlineDate = deadline?.date?.toString(),
                deadlineTime = deadline?.time?.let(::formatTime),
                planDate = task.planDate?.takeIf { kept(TaskField.PLAN_DATE) }?.toString(),
            )
        }

        /** The route accepts at most [EnrichRoute.MAX_TEXT_LENGTH] characters; longer titles are cut, never split mid-character. */
        fun clip(text: String, max: Int = EnrichRoute.MAX_TEXT_LENGTH): String {
            if (text.length <= max) return text
            val end = if (Character.isHighSurrogate(text[max - 1])) max - 1 else max
            return text.substring(0, end).trimEnd()
        }

        private fun formatTime(time: LocalTime): String = "%02d:%02d".format(Locale.ROOT, time.hour, time.minute)
    }
}

/**
 * Language hint of a task text for the model (tech plan §17.3): letters that only Russian or only Ukrainian has, else
 * the parser languages when just one of them is on; Latin-only text is English. The model reads the text itself, so
 * an unknown language is simply null.
 */
internal object LanguageHint {
    private const val RUSSIAN_ONLY = "ыэъё"
    private const val UKRAINIAN_ONLY = "іїєґ"

    fun detect(text: String, parserLanguages: Set<String>): String? {
        var russian = 0
        var ukrainian = 0
        var cyrillic = 0
        var latin = 0
        for (char in text.lowercase(Locale.ROOT)) {
            when {
                char in RUSSIAN_ONLY -> russian++
                char in UKRAINIAN_ONLY -> ukrainian++
            }
            when {
                Character.UnicodeBlock.of(char) == Character.UnicodeBlock.CYRILLIC -> cyrillic++
                char in 'a'..'z' -> latin++
            }
        }
        return when {
            cyrillic == 0 && latin == 0 -> null
            cyrillic < latin -> ENGLISH
            russian > 0 && ukrainian == 0 -> RUSSIAN
            ukrainian > 0 && russian == 0 -> UKRAINIAN
            russian > 0 -> null
            else -> parserLanguages.filter { it == RUSSIAN || it == UKRAINIAN }.singleOrNull()
        }
    }

    private const val ENGLISH = "en"
    private const val RUSSIAN = "ru"
    private const val UKRAINIAN = "uk"
}

/** Turns a checked `/v1/enrich` answer into the values [AiCommands.apply] may fill (AI-1, §17.3). */
internal object EnrichResponseMapper {
    /** A deadline with a time belongs to [zone], the zone the task is enriched in (tech plan §7.8). */
    fun toFill(response: EnrichResponse, zone: ZoneId): AiFill {
        val estimate = response.estimate?.let { value -> Estimate.entries.firstOrNull { it.name == value } }
        val deadlineDate = date(response.deadlineDate)
        val deadlineTime = deadlineDate?.let { time(response.deadlineTime) }
        return AiFill(
            estimate = estimate,
            estimateConfidence = response.estimateConfidence?.takeIf { estimate != null && it.isFinite() }?.coerceIn(0.0, 1.0),
            deadline = deadlineDate?.let { Deadline(it, deadlineTime, if (deadlineTime != null) zone else null) },
            planDate = date(response.planDate),
        )
    }

    private fun date(value: String?): LocalDate? = parse(value) { LocalDate.parse(it) }

    private fun time(value: String?): LocalTime? = parse(value) { LocalTime.parse(it) }

    private inline fun <T> parse(value: String?, parser: (String) -> T): T? {
        if (value.isNullOrBlank()) return null
        return try {
            parser(value.trim())
        } catch (e: DateTimeException) {
            null
        }
    }
}
