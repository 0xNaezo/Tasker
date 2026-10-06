package app.tasker.core.data.mapper

import kotlinx.serialization.json.Json

/** JSON settings shared by the data layer: tolerant to unknown keys so older app versions read newer files. */
val DataJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
