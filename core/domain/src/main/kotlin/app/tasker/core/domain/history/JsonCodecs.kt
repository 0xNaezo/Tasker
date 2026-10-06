package app.tasker.core.domain.history

import app.tasker.core.model.Deadline
import app.tasker.core.model.FieldSource
import app.tasker.core.model.ReminderOffsets
import app.tasker.core.model.TaskField
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Encodes a column value to JSON for the event log and back (tech plan §7.5 `changes`). */
interface ValueCodec<T> {
    fun encode(value: T): JsonElement

    fun decode(json: JsonElement): T
}

private fun JsonElement.stringOrNull(): String? = if (this is JsonNull) null else jsonPrimitive.contentOrNull

object Codecs {
    val string = object : ValueCodec<String> {
        override fun encode(value: String) = JsonPrimitive(value)

        override fun decode(json: JsonElement) = json.stringOrNull().orEmpty()
    }

    val nullableString = object : ValueCodec<String?> {
        override fun encode(value: String?) = value?.let(::JsonPrimitive) ?: JsonNull

        override fun decode(json: JsonElement) = json.stringOrNull()
    }

    val int = object : ValueCodec<Int> {
        override fun encode(value: Int) = JsonPrimitive(value)

        override fun decode(json: JsonElement) = (json as? JsonPrimitive)?.intOrNull ?: 0
    }

    val nullableInt = object : ValueCodec<Int?> {
        override fun encode(value: Int?) = value?.let(::JsonPrimitive) ?: JsonNull

        override fun decode(json: JsonElement) = (json as? JsonPrimitive)?.intOrNull
    }

    val long = object : ValueCodec<Long> {
        override fun encode(value: Long) = JsonPrimitive(value)

        override fun decode(json: JsonElement) = (json as? JsonPrimitive)?.longOrNull ?: 0L
    }

    val boolean = object : ValueCodec<Boolean> {
        override fun encode(value: Boolean) = JsonPrimitive(value)

        override fun decode(json: JsonElement) = (json as? JsonPrimitive)?.booleanOrNull ?: false
    }

    val nullableDate = object : ValueCodec<LocalDate?> {
        override fun encode(value: LocalDate?) = value?.let { JsonPrimitive(it.toString()) } ?: JsonNull

        override fun decode(json: JsonElement) = json.stringOrNull()?.let(LocalDate::parse)
    }

    val nullableInstant = object : ValueCodec<Instant?> {
        override fun encode(value: Instant?) = value?.let { JsonPrimitive(it.toString()) } ?: JsonNull

        override fun decode(json: JsonElement) = json.stringOrNull()?.let(Instant::parse)
    }

    val instant = object : ValueCodec<Instant> {
        override fun encode(value: Instant) = JsonPrimitive(value.toString())

        override fun decode(json: JsonElement) = json.stringOrNull()?.let(Instant::parse) ?: Instant.EPOCH
    }

    inline fun <reified E : Enum<E>> enumCodec(): ValueCodec<E> = object : ValueCodec<E> {
        override fun encode(value: E) = JsonPrimitive(value.name)

        override fun decode(json: JsonElement): E = enumValueOf(json.stringOrNullPublic().orEmpty())
    }

    inline fun <reified E : Enum<E>> nullableEnumCodec(): ValueCodec<E?> = object : ValueCodec<E?> {
        override fun encode(value: E?) = value?.let { JsonPrimitive(it.name) } ?: JsonNull

        override fun decode(json: JsonElement): E? = json.stringOrNullPublic()?.let { enumValueOf<E>(it) }
    }

    val deadline = object : ValueCodec<Deadline?> {
        override fun encode(value: Deadline?): JsonElement = value?.let {
            JsonObject(
                buildMap {
                    put("date", JsonPrimitive(it.date.toString()))
                    it.time?.let { time -> put("time", JsonPrimitive(time.toString())) }
                    it.zone?.let { zone -> put("zone", JsonPrimitive(zone.id)) }
                },
            )
        } ?: JsonNull

        override fun decode(json: JsonElement): Deadline? {
            if (json is JsonNull) return null
            val obj = json.jsonObject
            return Deadline(
                date = LocalDate.parse(obj.getValue("date").jsonPrimitive.content),
                time = obj["time"]?.stringOrNull()?.let(LocalTime::parse),
                zone = obj["zone"]?.stringOrNull()?.let(ZoneId::of),
            )
        }
    }

    val stringList = object : ValueCodec<List<String>> {
        override fun encode(value: List<String>) = JsonArray(value.map(::JsonPrimitive))

        override fun decode(json: JsonElement) =
            if (json is JsonNull) emptyList() else json.jsonArray.mapNotNull { it.stringOrNull() }
    }

    val reminders = object : ValueCodec<ReminderOffsets?> {
        override fun encode(value: ReminderOffsets?) =
            value?.let { JsonArray(it.minutesBefore.map(::JsonPrimitive)) } ?: JsonNull

        override fun decode(json: JsonElement) = if (json is JsonNull) {
            null
        } else {
            ReminderOffsets(json.jsonArray.mapNotNull { (it as? JsonPrimitive)?.intOrNull })
        }
    }

    val fieldSources = object : ValueCodec<Map<TaskField, FieldSource>> {
        override fun encode(value: Map<TaskField, FieldSource>) =
            JsonObject(value.entries.sortedBy { it.key }.associate { it.key.name to JsonPrimitive(it.value.name) })

        override fun decode(json: JsonElement): Map<TaskField, FieldSource> = if (json is JsonNull) {
            emptyMap()
        } else {
            json.jsonObject.entries.mapNotNull { (key, value) ->
                val field = TaskField.entries.firstOrNull { it.name == key }
                val source = FieldSource.entries.firstOrNull { it.name == value.stringOrNull() }
                if (field != null && source != null) field to source else null
            }.toMap()
        }
    }
}

/** Public helper for inline codecs. */
fun JsonElement.stringOrNullPublic(): String? = if (this is JsonNull) null else jsonPrimitive.contentOrNull
