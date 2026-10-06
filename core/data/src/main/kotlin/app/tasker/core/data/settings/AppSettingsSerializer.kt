package app.tasker.core.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.tasker.core.data.mapper.DataJson
import app.tasker.core.model.AppSettings
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException

/** Typed DataStore serializer for settings (tech plan §7.7); a broken file is replaced with defaults. */
object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings {
        val text = input.readBytes().decodeToString()
        if (text.isBlank()) return defaultValue
        return try {
            DataJson.decodeFromString(AppSettings.serializer(), text)
        } catch (e: SerializationException) {
            throw CorruptionException("Settings file is not valid JSON", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Settings file has invalid values", e)
        }
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(DataJson.encodeToString(AppSettings.serializer(), t).encodeToByteArray())
    }
}
