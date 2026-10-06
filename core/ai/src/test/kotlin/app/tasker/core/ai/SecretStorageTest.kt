package app.tasker.core.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecretStorageTest {
    private val dir: File = Files.createTempDirectory("vault").toFile()
    private val aead = testAead()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `secrets are encrypted at rest and survive a restart`() = runTest {
        val vault = testVault(dir, aead)

        vault.write("proxy_install", "token-value-123".encodeToByteArray())

        val file = File(dir, "proxy_install")
        assertThat(file.exists()).isTrue()
        assertThat(String(file.readBytes(), Charsets.ISO_8859_1)).doesNotContain("token-value-123")
        assertThat(testVault(dir, aead).read("proxy_install")?.decodeToString()).isEqualTo("token-value-123")
        assertThat(dir.listFiles().orEmpty().map { it.name }).containsExactly("proxy_install")
    }

    @Test
    fun `a secret bound to another name or key does not decrypt and is removed`() = runTest {
        testVault(dir, aead).write("anthropic_api_key", "sk-ant-x".encodeToByteArray())
        File(dir, "anthropic_api_key").copyTo(File(dir, "proxy_install"))

        // Same key, other slot: the associated data does not match.
        assertThat(testVault(dir, aead).read("proxy_install")).isNull()
        assertThat(File(dir, "proxy_install").exists()).isFalse()
        // Another keyset (e.g. the Keystore key was lost): unreadable, removed.
        assertThat(testVault(dir, testAead()).read("anthropic_api_key")).isNull()
        assertThat(File(dir, "anthropic_api_key").exists()).isFalse()
    }

    @Test
    fun `secret names are restricted to plain file names`() = runTest {
        val vault = testVault(dir, aead)

        val error = runCatching { vault.write("../escape", byteArrayOf(1)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the key store reports presence as a flow and keeps the key across instances`() = runTest {
        val store = ApiKeyStore(testVault(dir, aead))

        store.hasKey.test {
            assertThat(awaitItem()).isFalse()
            store.save("\tsk-ant-api03-abc\n")
            assertThat(awaitItem()).isTrue()
            store.clear()
            assertThat(awaitItem()).isFalse()
            store.save("sk-ant-api03-def")
            assertThat(awaitItem()).isTrue()
        }

        val restarted = ApiKeyStore(testVault(dir, aead))
        assertThat(restarted.get()).isEqualTo("sk-ant-api03-def")
        assertThat(ApiKeyStore.normalize("  sk-ant-api03-abc ")).isEqualTo("sk-ant-api03-abc")
    }

    @Test
    fun `while the store cannot be opened, access fails and nothing is removed`() = runTest {
        testVault(dir, aead).write("proxy_install", "token-value-123".encodeToByteArray())
        var opens = 0
        val vault = SecretVault(
            dir,
            { if (++opens == 1) throw ProviderException("Keystore busy") else aead },
            Dispatchers.Unconfined,
        )

        val error = runCatching { vault.read("proxy_install") }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(File(dir, "proxy_install").exists()).isTrue()
        // The failure is not remembered: the next access opens the store again.
        assertThat(vault.read("proxy_install")?.decodeToString()).isEqualTo("token-value-123")
        assertThat(opens).isEqualTo(2)
    }

    @Test
    fun `a key that cannot be read right now counts as absent and is read again on the next access`() = runTest {
        testVault(dir, aead).write("anthropic_api_key", "sk-ant-api03-abc".encodeToByteArray())
        var keystoreWorks = false
        val store = ApiKeyStore(
            SecretVault(dir, { if (keystoreWorks) aead else throw GeneralSecurityException("locked") }, Dispatchers.Unconfined),
        )

        store.hasKey.test {
            assertThat(awaitItem()).isFalse()
            assertThat(store.get()).isNull()
            assertThat(File(dir, "anthropic_api_key").exists()).isTrue()

            keystoreWorks = true
            assertThat(store.get()).isEqualTo("sk-ant-api03-abc")
            assertThat(awaitItem()).isTrue()
        }
    }

    @Test
    fun `the Keystore-backed AEAD works and starts over only when its keyset is lost`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(TinkKeystoreAead.KEYSET_PREFS, Context.MODE_PRIVATE)
        val vault = SecretVault(dir, { TinkKeystoreAead.create(context) }, Dispatchers.Unconfined)

        vault.write("anthropic_api_key", "sk-ant-api03-xyz".encodeToByteArray())
        assertThat(vault.read("anthropic_api_key")?.decodeToString()).isEqualTo("sk-ant-api03-xyz")

        // A keyset that cannot be opened (e.g. restored from another device).
        prefs.edit().putString(TinkKeystoreAead.KEYSET_NAME, "00ff00ff").commit()

        // While the Keystore fails, the keyset may still be fine: it stays, and so do the secrets.
        val keystoreFails = SecretVault(dir, { TinkKeystoreAead.create(context) { false } }, Dispatchers.Unconfined)
        assertThat(runCatching { keystoreFails.read("anthropic_api_key") }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(File(dir, "anthropic_api_key").exists()).isTrue()
        assertThat(prefs.getString(TinkKeystoreAead.KEYSET_NAME, null)).isEqualTo("00ff00ff")

        // Lost for good (master key gone, or the Keystore works): a new keyset; old secrets read as absent.
        val afterReset = SecretVault(dir, { TinkKeystoreAead.create(context) { true } }, Dispatchers.Unconfined)
        assertThat(afterReset.read("anthropic_api_key")).isNull()
        assertThat(File(dir, "anthropic_api_key").exists()).isFalse()
        assertThat(prefs.getString(TinkKeystoreAead.KEYSET_NAME, null)).isNotEqualTo("00ff00ff")
        afterReset.write("anthropic_api_key", "sk-ant-api03-new".encodeToByteArray())
        assertThat(afterReset.read("anthropic_api_key")?.decodeToString()).isEqualTo("sk-ant-api03-new")
    }
}
