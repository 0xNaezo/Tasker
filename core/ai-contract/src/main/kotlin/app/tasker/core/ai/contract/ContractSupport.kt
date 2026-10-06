package app.tasker.core.ai.contract

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.Json

/** JSON configurations of the AI contract. */
object AiJson {
    /** App <-> backend wire format: unknown keys ignored (forward compatible), nulls omitted. */
    val wire: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Renders prompt input deterministically, with explicit nulls so the model sees every field. */
    internal val prompt: Json = Json {
        explicitNulls = true
        encodeDefaults = true
    }

    /** Parses the model's structured output; the schema already fixes the shape. */
    val modelOutput: Json = Json {
        ignoreUnknownKeys = true
    }
}

/** ISO-8601 parsing that returns null instead of throwing. */
internal object IsoValues {
    fun date(value: String?): LocalDate? = parse(value) { LocalDate.parse(it) }

    fun dateTime(value: String?): LocalDateTime? = parse(value) { LocalDateTime.parse(it) }

    /** Accepts "HH:mm" and "HH:mm:ss". */
    fun time(value: String?): LocalTime? = parse(value) { LocalTime.parse(it) }

    fun zone(value: String?): ZoneId? = parse(value) { ZoneId.of(it) }

    /** "HH:mm", the time format of the contract. */
    fun formatTime(time: LocalTime): String = time.hour.toString().padStart(2, '0') + ":" + time.minute.toString().padStart(2, '0')

    private inline fun <T> parse(value: String?, parser: (String) -> T): T? {
        if (value.isNullOrBlank()) return null
        return try {
            parser(value.trim())
        } catch (e: DateTimeException) {
            null
        }
    }
}
