package app.tasker.core.ai.openrouter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Reads OpenRouter answers: unknown fields are ignored, a null where a default exists takes the default. */
internal val OpenRouterJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** The parts of a chat completion the runner reads. Error messages are never read: they may quote the request. */
@Serializable
internal data class ChatCompletion(
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
    val error: ApiError? = null,
)

@Serializable
internal data class Choice(
    @SerialName("finish_reason") val finishReason: String? = null,
    @SerialName("native_finish_reason") val nativeFinishReason: String? = null,
    val message: Message? = null,
    val error: ApiError? = null,
)

/** [content] is a string, rarely an array of content parts, or null when the model wrote nothing. */
@Serializable
internal data class Message(
    val content: JsonElement? = null,
    val refusal: String? = null,
)

/** Always included by OpenRouter. [promptTokens] count cached and cache-written tokens too; [cost] is in USD. */
@Serializable
internal data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null,
    val cost: Double? = null,
)

@Serializable
internal data class PromptTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Long = 0,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long = 0,
)

/** The body of a failed call. */
@Serializable
internal data class ErrorResponse(val error: ApiError? = null)

/**
 * [code] is usually the HTTP status. [metadata] carries provider details and, for moderation, the flagged input: only
 * its keys are looked at.
 */
@Serializable
internal data class ApiError(
    val code: JsonElement? = null,
    val metadata: JsonObject? = null,
)
