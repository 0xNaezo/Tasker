package app.tasker.core.ai

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.integration.android.AndroidKeystore
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Small secrets at rest (tech plan §21): the user's API key and the proxy install token. Each secret is one file,
 * encrypted with a Tink AEAD (AES-256-GCM) whose keyset is wrapped by an Android Keystore master key, and bound to
 * its name as associated data.
 *
 * Files live in `noBackupFilesDir`, so Auto Backup never copies them: Keystore keys do not leave the device, and a
 * restored copy could not be decrypted anyway. A secret that no longer decrypts (keyset replaced, tampering) reads as
 * absent and is deleted; while the Keystore itself is unavailable, reads and writes fail with [IOException] and
 * nothing is deleted. Values are never logged.
 */
class SecretVault(
    private val directory: File,
    private val aeadFactory: () -> Aead,
    private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private var aead: Aead? = null

    /**
     * The secret [name], or null when it is not stored or cannot be decrypted any more.
     *
     * @throws IOException when the secret store is unavailable right now (Keystore error, unreadable file).
     */
    @Throws(IOException::class)
    suspend fun read(name: String): ByteArray? = access(name) { file ->
        if (!file.exists()) return@access null
        val ciphertext = file.readBytes()
        val cipher = cipher()
        try {
            cipher.decrypt(ciphertext, associatedData(name))
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "A stored secret cannot be decrypted (${e.javaClass.simpleName}); it is removed")
            file.delete()
            null
        }
    }

    /**
     * Stores [value] under [name], replacing the previous value atomically.
     *
     * @throws IOException when the secret cannot be encrypted or written.
     */
    @Throws(IOException::class)
    suspend fun write(name: String, value: ByteArray): Unit = access(name) { file ->
        val ciphertext = try {
            cipher().encrypt(value, associatedData(name))
        } catch (e: GeneralSecurityException) {
            throw IOException("Cannot encrypt the secret", e)
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create the secrets directory")
        val temp = File(directory, "$name$TEMP_SUFFIX")
        temp.writeBytes(ciphertext)
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("Cannot store the secret")
        }
    }

    /** Removes the secret [name]; needs no key, so it works even while the Keystore is unavailable. */
    suspend fun delete(name: String): Unit = access(name) { file ->
        file.delete()
        Unit
    }

    private suspend fun <T> access(name: String, block: (File) -> T): T {
        require(NAME.matches(name)) { "Invalid secret name" }
        return withContext(io) { mutex.withLock { block(File(directory, name)) } }
    }

    /**
     * Created on first use, on the IO dispatcher: opening the Keystore and the keyset does disk and binder work.
     * A failure is not remembered, so the next access tries again.
     */
    private fun cipher(): Aead = aead ?: try {
        aeadFactory().also { aead = it }
    } catch (e: Exception) {
        // Keystore implementations fail with checked and runtime exceptions alike (ProviderException and others).
        throw IOException("The secret store is unavailable", e)
    }

    private fun associatedData(name: String): ByteArray = "$AD_PREFIX$name".encodeToByteArray()

    companion object {
        /** Directory inside `noBackupFilesDir`. */
        const val DIRECTORY = "ai_secrets"
        private const val TAG = "SecretVault"
        private const val TEMP_SUFFIX = ".tmp"
        private const val AD_PREFIX = "tasker-secret:"
        private val NAME = Regex("[a-z0-9_]{1,64}")
    }
}

/**
 * Tink AEAD for [SecretVault]: an AES-256-GCM keyset kept in private SharedPreferences ([KEYSET_PREFS]), encrypted by
 * the Android Keystore key [MASTER_KEY_URI] (`AndroidKeysetManager`).
 *
 * When the stored keyset cannot be opened, it is replaced by a new one only if it is lost for good: its master key is
 * gone (e.g. the prefs were restored from a backup of another device, or the app was reinstalled) or the Keystore
 * works but the keyset itself is broken. A Keystore that fails right now may recover, so the keyset is kept then.
 * After a replacement, the old secrets read as absent.
 */
object TinkKeystoreAead {
    const val MASTER_KEY_URI = "android-keystore://tasker_ai_secrets_master_key"
    const val KEYSET_NAME = "tasker_ai_secrets_keyset"

    /** SharedPreferences file of the wrapped keyset; the app may exclude it from backups. */
    const val KEYSET_PREFS = "tasker_ai_secrets_keyset_prefs"

    private const val TAG = "TinkKeystoreAead"
    private val MASTER_KEY_ALIAS = MASTER_KEY_URI.removePrefix("android-keystore://")

    @Throws(GeneralSecurityException::class)
    fun create(context: Context): Aead = create(context, ::keysetIsLost)

    /** [keysetIsLost] decides whether a keyset that cannot be opened may be replaced; a seam for tests. */
    @Throws(GeneralSecurityException::class)
    internal fun create(context: Context, keysetIsLost: () -> Boolean): Aead {
        AeadConfig.register()
        val manager = try {
            manager(context)
        } catch (e: GeneralSecurityException) {
            recover(context, e, keysetIsLost)
        } catch (e: IOException) {
            recover(context, e, keysetIsLost)
        }
        if (!manager.isUsingKeystore) Log.w(TAG, "Android Keystore is unavailable; the secrets keyset is not hardware-wrapped")
        return manager.keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    /**
     * The keyset could not be opened. While the Keystore fails, the keyset is kept and the error goes to the caller.
     * Otherwise the keyset gets one more try, in case the error was transient, and is replaced if that fails too.
     */
    private fun recover(context: Context, cause: Exception, keysetIsLost: () -> Boolean): AndroidKeysetManager {
        if (!keysetIsLost()) throw GeneralSecurityException("Cannot open the secrets keyset", cause)
        return try {
            manager(context)
        } catch (e: GeneralSecurityException) {
            reset(context, e)
        } catch (e: IOException) {
            reset(context, e)
        }
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    private fun manager(context: Context): AndroidKeysetManager = AndroidKeysetManager.Builder()
        .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
        .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
        .withMasterKeyUri(MASTER_KEY_URI)
        .build()

    /** True when the master key is gone, or when it works and so the keyset must be the broken part. */
    private fun keysetIsLost(): Boolean = try {
        if (!AndroidKeystore.hasKey(MASTER_KEY_ALIAS)) {
            true
        } else {
            val master = AndroidKeystore.getAead(MASTER_KEY_ALIAS)
            master.decrypt(master.encrypt(PROBE, PROBE), PROBE).contentEquals(PROBE)
        }
    } catch (e: Exception) {
        // GeneralSecurityException, ProviderException or another runtime error of the Keystore implementation.
        Log.w(TAG, "Android Keystore fails (${e.javaClass.simpleName}); the secrets keyset is kept")
        false
    }

    // commit(): the old keyset must be gone before the new one is generated; this runs on the IO dispatcher.
    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun reset(context: Context, cause: Exception): AndroidKeysetManager {
        Log.w(TAG, "The secrets keyset is lost (${cause.javaClass.simpleName}); creating a new one")
        context.getSharedPreferences(KEYSET_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        return try {
            manager(context)
        } catch (e: IOException) {
            throw GeneralSecurityException("Cannot create the secrets keyset", e)
        }
    }

    private val PROBE = "tasker-keystore-probe".encodeToByteArray()
}
