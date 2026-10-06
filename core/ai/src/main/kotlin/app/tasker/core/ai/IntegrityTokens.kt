package app.tasker.core.ai

import android.content.Context
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.IntegrityBinding
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManager
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.model.IntegrityErrorCode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Outcome of a Play Integrity token request. */
sealed interface IntegrityTokenResult {
    /** Not a data class: the token must not end up in logs. */
    class Token(val value: String) : IntegrityTokenResult {
        override fun toString(): String = "Token(<redacted>)"
    }

    /** [detail] is a short code such as `integrity_-3`. */
    data class Failed(val kind: FailureKind, val detail: String) : IntegrityTokenResult
}

/** Play Integrity tokens for `/v1/install` (tech plan §18.1). */
fun interface IntegrityTokenProvider {
    /** A token whose request binding is [requestHash] ([IntegrityBinding.requestHash] of the install id). */
    suspend fun requestToken(requestHash: String): IntegrityTokenResult
}

/**
 * Classic Play Integrity API: one request per install token (every 30 days), so no warm-up provider is needed and
 * the cloud project number is optional for apps distributed through Google Play. The request hash travels as the
 * nonce (URL-safe Base64, padded), which the backend compares with [IntegrityBinding.requestHash].
 */
@Singleton
class PlayIntegrityTokenProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val environment: AiEnvironment,
) : IntegrityTokenProvider {
    private val manager: IntegrityManager by lazy { IntegrityManagerFactory.create(context) }

    override suspend fun requestToken(requestHash: String): IntegrityTokenResult {
        val builder = IntegrityTokenRequest.builder().setNonce(nonceOf(requestHash))
        environment.integrityCloudProjectNumber?.let { builder.setCloudProjectNumber(it) }
        return try {
            IntegrityTokenResult.Token(manager.requestIntegrityToken(builder.build()).await().token())
        } catch (e: IntegrityServiceException) {
            IntegrityTokenResult.Failed(kindOf(e.errorCode), "integrity_${e.errorCode}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Play services report other problems as ApiException or runtime exceptions; none of them is retried here.
            IntegrityTokenResult.Failed(FailureKind.AUTH, "integrity_${e.javaClass.simpleName}")
        }
    }

    internal companion object {
        /** Base64 padding: the classic API expects the nonce in the form `Base64.URL_SAFE | Base64.NO_WRAP` produces. */
        fun nonceOf(requestHash: String): String {
            val padding = (BASE64_BLOCK - requestHash.length % BASE64_BLOCK) % BASE64_BLOCK
            return requestHash + "=".repeat(padding)
        }

        /**
         * Transient errors are retried with backoff (Play Integrity guidance); everything else means this device or
         * build cannot attest, which is a configuration problem rather than a problem of the task.
         */
        fun kindOf(errorCode: Int): FailureKind = when (errorCode) {
            IntegrityErrorCode.NETWORK_ERROR -> FailureKind.NETWORK
            IntegrityErrorCode.TOO_MANY_REQUESTS -> FailureKind.RATE_LIMIT
            IntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE,
            IntegrityErrorCode.CLIENT_TRANSIENT_ERROR,
            IntegrityErrorCode.INTERNAL_ERROR,
            IntegrityErrorCode.CANNOT_BIND_TO_SERVICE,
            -> FailureKind.OVERLOADED
            else -> FailureKind.AUTH
        }

        private const val BASE64_BLOCK = 4
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
