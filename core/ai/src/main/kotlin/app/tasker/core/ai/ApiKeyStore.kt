package app.tasker.core.ai

import android.util.Log
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The user's own OpenRouter API key for the direct mode (tech plan §17.2, §21; ADR 0011): encrypted with Tink and the
 * Android Keystore ([SecretVault]), used only for requests to OpenRouter, removed with one tap. The key is never logged
 * and never leaves this class except for the request header.
 *
 * While the store cannot be read (the Keystore fails), the key counts as absent: the queue stops and keeps its tasks,
 * as without a key, and the next access reads the store again.
 */
@Singleton
class ApiKeyStore @Inject constructor(private val vault: SecretVault) {
    private val mutex = Mutex()
    private val present = MutableStateFlow(false)

    /** Whether the stored key was read, saved or cleared; until then [key] means nothing. */
    @Volatile
    private var loaded = false

    @Volatile
    private var key: String? = null

    /** Whether a key is stored; the first collection reads the store. */
    val hasKey: Flow<Boolean> = present.onStart { load() }

    /** The stored key, or null when none is stored or the store cannot be read right now. */
    suspend fun get(): String? = load()

    /**
     * Stores [key] after [normalize]; replaces a previous key.
     *
     * @throws IllegalArgumentException when the key is blank or contains whitespace or control characters.
     * @throws IOException when the key cannot be encrypted or written.
     */
    @Throws(IOException::class)
    suspend fun save(key: String) {
        val normalized = normalize(key)
        mutex.withLock {
            vault.write(SECRET_NAME, normalized.encodeToByteArray())
            this.key = normalized
            loaded = true
            present.value = true
        }
    }

    suspend fun clear() {
        mutex.withLock {
            vault.delete(SECRET_NAME)
            key = null
            loaded = true
            present.value = false
        }
    }

    private suspend fun load(): String? {
        if (loaded) return key
        return mutex.withLock {
            if (!loaded) {
                try {
                    key = vault.read(SECRET_NAME)?.decodeToString()?.takeIf { it.isNotBlank() }
                    loaded = true
                    present.value = key != null
                } catch (e: IOException) {
                    Log.w(TAG, "The API key cannot be read right now (${e.javaClass.simpleName})")
                }
            }
            key
        }
    }

    companion object {
        private const val TAG = "ApiKeyStore"
        private const val SECRET_NAME = "openrouter_api_key"
        const val MAX_LENGTH = 1_024

        /** Prefix of OpenRouter API keys; only a hint for the UI, other formats are accepted. */
        const val OPENROUTER_KEY_PREFIX = "sk-or-"

        /**
         * Trims [key]. Other formats than `sk-or-…` are accepted, but a key must be non-blank, at most [MAX_LENGTH]
         * characters and printable ASCII without spaces: it is sent as an HTTP header.
         */
        fun normalize(key: String): String {
            val trimmed = key.trim()
            require(trimmed.isNotEmpty()) { "The API key is empty" }
            require(trimmed.length <= MAX_LENGTH) { "The API key is too long" }
            require(trimmed.all { it in '!'..'~' }) { "The API key may contain only printable ASCII characters without spaces" }
            return trimmed
        }

        /** True for keys that look like OpenRouter API keys; the UI may use it for a soft warning. */
        fun looksLikeOpenRouterKey(key: String): Boolean = key.trim().startsWith(OPENROUTER_KEY_PREFIX)
    }
}
