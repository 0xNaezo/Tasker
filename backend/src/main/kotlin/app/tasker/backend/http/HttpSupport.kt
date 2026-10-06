package app.tasker.backend.http

import app.tasker.core.ai.contract.AiJson
import app.tasker.core.ai.contract.ErrorBody
import app.tasker.core.ai.contract.ErrorCodes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readBuffer
import java.time.Duration
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/** 400 with a message that names fields only; request content is never echoed or logged. */
class InvalidRequestException(message: String) : RuntimeException(message)

class BodyTooLargeException : RuntimeException("Request body too large")

/** Reads a JSON body of at most [maxBytes] bytes, whether or not the client declared Content-Length. */
suspend fun <T> ApplicationCall.receiveJson(serializer: KSerializer<T>, maxBytes: Long): T {
    val declared = request.contentLength()
    if (declared != null && declared > maxBytes) throw BodyTooLargeException()
    val bytes = receiveChannel().readBuffer(maxBytes + 1).readByteArray()
    if (bytes.size > maxBytes) throw BodyTooLargeException()
    return try {
        AiJson.wire.decodeFromString(serializer, bytes.decodeToString())
    } catch (e: SerializationException) {
        throw InvalidRequestException("Malformed JSON body")
    } catch (e: IllegalArgumentException) {
        throw InvalidRequestException("Malformed JSON body")
    }
}

suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String, retryAfter: Duration? = null) {
    if (retryAfter != null) response.header(HttpHeaders.RetryAfter, retryAfter.seconds.coerceAtLeast(1).toString())
    respond(status, ErrorBody(code, message))
}

suspend fun ApplicationCall.respondInvalid(problems: List<String>) =
    respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_REQUEST, "Invalid fields: ${problems.joinToString()}")

/** Model usage of the call, read by the access log (tokens and cost only). */
data class CallUsage(
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheCreationTokens: Long,
    val costMicroUsd: Long,
    val outcome: String,
)

val CallUsageKey: AttributeKey<CallUsage> = AttributeKey("tasker.callUsage")
