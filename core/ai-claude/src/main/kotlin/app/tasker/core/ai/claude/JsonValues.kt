package app.tasker.core.ai.claude

import com.anthropic.core.JsonValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Converts a kotlinx JSON tree (the contract's schema) into the SDK's [JsonValue]. Object key order is kept
 * (LinkedHashMap), so the serialized schema stays byte-identical across requests, as prompt caching needs.
 * No reflection: the schema is passed explicitly instead of being generated from classes.
 */
internal fun JsonElement.toJsonValue(): JsonValue = JsonValue.from(toPlain())

private fun JsonElement.toPlain(): Any? = when (this) {
    JsonNull -> null
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        longOrNull != null -> longOrNull
        else -> doubleOrNull ?: content
    }
    is JsonObject -> entries.associateTo(LinkedHashMap()) { (key, value) -> key to value.toPlain() }
    is JsonArray -> map { it.toPlain() }
}
