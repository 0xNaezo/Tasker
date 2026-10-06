package app.tasker.core.ai.claude

import app.tasker.core.ai.contract.AiJson
import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.EnrichRoute
import app.tasker.core.ai.contract.FailureKind
import app.tasker.core.ai.contract.RouteContract
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.ai.contract.RouteSettings
import app.tasker.core.ai.contract.RouteUsage
import com.anthropic.client.AnthropicClient
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.UnprocessableEntityException
import com.anthropic.models.ErrorType
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlin.jvm.optionals.getOrNull
import kotlinx.serialization.SerializationException

/**
 * Runs the AI routes of `core:ai-contract` against the Claude API with the official Java SDK
 * (tech plan §17.4). Used by the backend proxy and by the app's direct mode with the user's own key.
 *
 * Every request:
 * - targets the beta Messages API with `fallbacks: "default"` and the `server-side-fallback-2026-07-01`
 *   beta header, so a classifier refusal is retried server-side on the recommended model;
 * - sends no `thinking` field (thinking is always on for Claude Opus 5.5) and sets `output_config.effort`
 *   explicitly, because the model's default is `medium`;
 * - asks for structured output with the route's JSON Schema (`output_config.format`), never forced
 *   `tool_choice`;
 * - puts the stable system prompt first with `cache_control`, and the variable data in the user turn.
 *
 * Responses are read by stop reason first (`refusal`, `max_tokens`), then by block type: thinking blocks
 * may come first, only text blocks carry the answer. Calls are blocking: run them off the main thread.
 * The runner never throws for API or network problems; it returns [RouteResult.Failed].
 */
class ClaudeRouteRunner(
    private val client: AnthropicClient,
    private val settings: RouteSettings = EnrichRoute.defaultSettings,
) {
    /** `/v1/enrich` with the settings this runner was created with. */
    fun enrich(request: EnrichRequest): RouteResult<EnrichResponse> = run(EnrichRoute, request, settings)

    /** Runs any route contract; future routes (§17.3) reuse this unchanged. */
    fun <Req, Res> run(
        contract: RouteContract<Req, Res>,
        request: Req,
        settings: RouteSettings = contract.route.defaultSettings,
    ): RouteResult<Res> {
        val problems = contract.requestProblems(request)
        if (problems.isNotEmpty()) {
            return RouteResult.Failed(FailureKind.BAD_REQUEST, detail = "invalid request fields: ${problems.joinToString()}")
        }
        return when (val sent = send(buildParams(contract, request, settings))) {
            is Sent.Answered -> try {
                interpret(contract, request, settings.model, sent.message)
            } catch (e: AnthropicInvalidDataException) {
                RouteResult.Failed(FailureKind.INVALID_OUTPUT, detail = e.javaClass.simpleName)
            }
            is Sent.NotAnswered -> sent.failure
        }
    }

    /** The SDK call; typed SDK exceptions are caught most-specific first and become failures. */
    private fun send(params: MessageCreateParams): Sent = try {
        Sent.Answered(client.beta().messages().create(params))
    } catch (e: AnthropicServiceException) {
        Sent.NotAnswered(RouteResult.Failed(classify(e), detail = "${e.javaClass.simpleName}(${e.statusCode()})"))
    } catch (e: AnthropicIoException) {
        Sent.NotAnswered(RouteResult.Failed(FailureKind.NETWORK, detail = e.javaClass.simpleName))
    } catch (e: AnthropicRetryableException) {
        Sent.NotAnswered(RouteResult.Failed(FailureKind.NETWORK, detail = e.javaClass.simpleName))
    } catch (e: AnthropicInvalidDataException) {
        Sent.NotAnswered(RouteResult.Failed(FailureKind.INVALID_OUTPUT, detail = e.javaClass.simpleName))
    } catch (e: AnthropicException) {
        Sent.NotAnswered(RouteResult.Failed(FailureKind.UNKNOWN, detail = e.javaClass.simpleName))
    } catch (e: RuntimeException) {
        Sent.NotAnswered(RouteResult.Failed(FailureKind.UNKNOWN, detail = e.javaClass.simpleName))
    }

    private sealed interface Sent {
        class Answered(val message: BetaMessage) : Sent

        class NotAnswered(val failure: RouteResult.Failed) : Sent
    }

    internal fun <Req, Res> buildParams(
        contract: RouteContract<Req, Res>,
        request: Req,
        settings: RouteSettings,
    ): MessageCreateParams {
        val outputConfig = BetaOutputConfig.builder()
            .effort(BetaOutputConfig.Effort.of(settings.effort))
            .format(BetaJsonOutputFormat.builder().schema(outputSchema(contract)).build())
            .build()
        val system = BetaTextBlockParam.builder()
            .text(contract.systemPrompt)
            .cacheControl(BetaCacheControlEphemeral.builder().build())
            .build()
        val builder = MessageCreateParams.builder()
            .model(settings.model)
            .maxTokens(settings.maxTokens)
            .outputConfig(outputConfig)
            .systemOfBetaTextBlockParams(listOf(system))
            .addUserMessage(contract.userMessage(request))
        if (settings.fallbacks) {
            builder.addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01).fallbacksDefault()
        }
        return builder.build()
    }

    private fun <Req, Res> interpret(
        contract: RouteContract<Req, Res>,
        request: Req,
        requestedModel: String,
        message: BetaMessage,
    ): RouteResult<Res> {
        val usage = usageOf(message, requestedModel)
        return when (val stopReason = message.stopReason().getOrNull()) {
            BetaStopReason.REFUSAL -> RouteResult.Refused(
                category = message.stopDetails().flatMap { it.category() }.map { it.asString() }.getOrNull(),
                usage = usage,
            )
            BetaStopReason.MAX_TOKENS, BetaStopReason.MODEL_CONTEXT_WINDOW_EXCEEDED ->
                RouteResult.Failed(FailureKind.TRUNCATED, usage, "stop_reason=${stopReason.asString()}")
            BetaStopReason.END_TURN, BetaStopReason.STOP_SEQUENCE -> parse(contract, request, message, usage)
            else -> RouteResult.Failed(FailureKind.INVALID_OUTPUT, usage, "stop_reason=${stopReason?.asString()}")
        }
    }

    private fun <Req, Res> parse(
        contract: RouteContract<Req, Res>,
        request: Req,
        message: BetaMessage,
        usage: RouteUsage,
    ): RouteResult<Res> {
        // Structured output arrives as one text block; thinking (and fallback) blocks are skipped by type.
        val text = message.content()
            .mapNotNull { block -> block.text().getOrNull()?.text() }
            .lastOrNull { it.isNotBlank() }
            ?: return RouteResult.Failed(FailureKind.INVALID_OUTPUT, usage, "no text block")
        val raw = try {
            AiJson.modelOutput.decodeFromString(contract.responseSerializer, text)
        } catch (e: SerializationException) {
            return RouteResult.Failed(FailureKind.INVALID_OUTPUT, usage, e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            return RouteResult.Failed(FailureKind.INVALID_OUTPUT, usage, e.javaClass.simpleName)
        }
        return RouteResult.Success(contract.validate(request, raw), usage)
    }

    internal companion object {
        /** Maps HTTP errors to failure kinds, most specific type first (see shared/error-codes in the API docs). */
        fun classify(error: AnthropicServiceException): FailureKind = when (error) {
            is UnauthorizedException, is PermissionDeniedException -> FailureKind.AUTH
            is RateLimitException -> FailureKind.RATE_LIMIT
            is BadRequestException, is NotFoundException, is UnprocessableEntityException -> FailureKind.BAD_REQUEST
            is InternalServerException -> FailureKind.OVERLOADED
            else -> classifyByType(error)
        }

        private fun classifyByType(error: AnthropicServiceException): FailureKind = when (error.errorType().getOrNull()) {
            ErrorType.AUTHENTICATION_ERROR, ErrorType.PERMISSION_ERROR, ErrorType.BILLING_ERROR -> FailureKind.AUTH
            ErrorType.RATE_LIMIT_ERROR -> FailureKind.RATE_LIMIT
            ErrorType.OVERLOADED_ERROR, ErrorType.API_ERROR -> FailureKind.OVERLOADED
            ErrorType.TIMEOUT_ERROR -> FailureKind.NETWORK
            ErrorType.INVALID_REQUEST_ERROR, ErrorType.NOT_FOUND_ERROR -> FailureKind.BAD_REQUEST
            else -> classifyByStatus(error.statusCode())
        }

        private fun classifyByStatus(status: Int): FailureKind = when (status) {
            HTTP_PAYMENT_REQUIRED -> FailureKind.AUTH
            HTTP_REQUEST_TIMEOUT -> FailureKind.NETWORK
            HTTP_PAYLOAD_TOO_LARGE -> FailureKind.BAD_REQUEST
            in HTTP_SERVER_ERRORS -> FailureKind.OVERLOADED
            else -> FailureKind.UNKNOWN
        }

        /**
         * Usage of the returned message. With server-side fallbacks, `usage.iterations` lists every attempt:
         * the last one produced the message, earlier ones declined and are billed at their own rates.
         * Malformed iteration details must not fail a call that was answered (and paid for), so they fall
         * back to the top-level usage.
         */
        @Suppress("SwallowedException")
        fun usageOf(message: BetaMessage, requestedModel: String): RouteUsage {
            val usage = message.usage()
            val servedModel = message.model().asString()
            val top = RouteUsage(
                model = servedModel,
                inputTokens = usage.inputTokens(),
                outputTokens = usage.outputTokens(),
                cacheReadTokens = usage.cacheReadInputTokens().orElse(0L),
                cacheCreationTokens = usage.cacheCreationInputTokens().orElse(0L),
            )
            val attempts = try {
                usage.iterations().getOrNull().orEmpty()
                    .filter { it.isMessage() || it.isFallbackMessage() }
                    .map { iteration ->
                        RouteUsage(
                            model = iteration.model().getOrNull()?.asString() ?: requestedModel,
                            inputTokens = iteration.inputTokens(),
                            outputTokens = iteration.outputTokens(),
                            cacheReadTokens = iteration.cacheReadInputTokens(),
                            cacheCreationTokens = iteration.cacheCreationInputTokens(),
                        )
                    }
            } catch (e: AnthropicInvalidDataException) {
                emptyList()
            }
            if (attempts.size <= 1) return top
            return attempts.last().copy(model = servedModel, declinedAttempts = attempts.dropLast(1))
        }

        private fun <Req, Res> outputSchema(contract: RouteContract<Req, Res>): BetaJsonOutputFormat.Schema {
            val schema = BetaJsonOutputFormat.Schema.builder()
            contract.outputSchema.forEach { (key, value) -> schema.putAdditionalProperty(key, value.toJsonValue()) }
            return schema.build()
        }

        private const val HTTP_PAYMENT_REQUIRED = 402
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private val HTTP_SERVER_ERRORS = 500..599
    }
}
