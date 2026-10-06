package app.tasker.core.ai.contract

import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class EnrichContractTest {

    private val request = EnrichRequest(
        text = "сдать отчёт до конца месяца",
        language = "ru",
        now = "2026-10-06T10:00",
        today = "2026-10-06",
        timeZone = "Europe/Kyiv",
        estimateScale = EstimateScale(15, 60, 180),
        extracted = ExtractedFields(planDate = "2026-10-07"),
        similarDone = listOf(SimilarTask("сдать отчёт за сентябрь", "M", 75)),
    )

    @Test
    fun `system prompt and schema are byte-identical across calls and requests`() {
        val prompt = EnrichRoute.systemPrompt
        val schema = EnrichRoute.outputSchema.toString()
        EnrichRoute.userMessage(request)
        EnrichRoute.userMessage(request.copy(text = "другая задача", today = "2026-12-31", now = "2026-12-31T23:00"))
        assertThat(EnrichRoute.systemPrompt).isEqualTo(prompt)
        assertThat(EnrichRoute.outputSchema.toString()).isEqualTo(schema)
        assertThat(Json.encodeToString(JsonObject.serializer(), EnrichRoute.outputSchema)).isEqualTo(schema)
    }

    /**
     * Any edit of the cached prefix changes cost and quality: re-run the eval (docs/ai-eval, tech plan §17.6),
     * then update this fingerprint.
     */
    @Test
    fun `prompt fingerprint is pinned so prompt changes are deliberate`() {
        val fingerprint = sha256(EnrichRoute.systemPrompt + "\n" + EnrichRoute.outputSchema.toString()).take(16)
        assertThat(fingerprint).isEqualTo(PROMPT_FINGERPRINT)
    }

    @Test
    fun `system prompt has no per-request data and is long enough to be cached`() {
        val prompt = EnrichRoute.systemPrompt
        assertThat(prompt).doesNotContain(request.text)
        assertThat(prompt).doesNotContain("\r")
        assertThat(prompt).isEqualTo(prompt.trim())
        // Claude Opus 5.5 caches prefixes from 512 tokens; ~4 characters per token is a safe lower bound.
        assertThat(prompt.length / 4).isAtLeast(512)
    }

    @Test
    fun `schema requires every field, forbids extra ones and matches the response DTO`() {
        val schema = EnrichRoute.outputSchema
        val properties = schema.getValue("properties").jsonObject.keys
        val required = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val dtoFields = EnrichResponse.serializer().descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName).toSet() }
        assertThat(properties).isEqualTo(dtoFields)
        assertThat(required).isEqualTo(dtoFields)
        assertThat(schema.getValue("additionalProperties")).isEqualTo(JsonPrimitive(false))
    }

    @Test
    fun `schema uses only keywords supported by structured outputs`() {
        val unsupported = setOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minLength", "maxLength")
        val keys = mutableListOf<String>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    keys += key
                    walk(value)
                }
                is JsonArray -> element.forEach(::walk)
                is JsonPrimitive, JsonNull -> Unit
            }
        }
        walk(EnrichRoute.outputSchema)
        assertThat(keys.toSet().intersect(unsupported)).isEmpty()
    }

    @Test
    fun `user message is deterministic JSON with the task last`() {
        val message = EnrichRoute.userMessage(request)
        assertThat(EnrichRoute.userMessage(request)).isEqualTo(message)
        val json = Json.parseToJsonElement(message).jsonObject
        assertThat(json.keys.last()).isEqualTo("task")
        assertThat(json.getValue("task").jsonPrimitive.content).isEqualTo(request.text)
        assertThat(json.getValue("weekday").jsonPrimitive.content).isEqualTo("Tuesday")
        assertThat(json.getValue("scaleMinutes").toString()).isEqualTo("""{"S":15,"M":60,"L":180}""")
        assertThat(json.getValue("extracted").jsonObject.getValue("deadlineDate")).isEqualTo(JsonNull)
        assertThat(message).contains("сдать отчёт за сентябрь")
    }

    @Test
    fun `valid request has no problems`() {
        assertThat(EnrichRoute.requestProblems(request)).isEmpty()
        assertThat(EnrichRoute.requestProblems(request.copy(language = null))).isEmpty()
    }

    @Test
    fun `request problems name fields without echoing text`() {
        val bad = request.copy(
            text = " ",
            language = "de",
            now = "now",
            timeZone = "Mars/Olympus",
            estimateScale = EstimateScale(60, 15, 180),
            extracted = ExtractedFields(estimate = "XL", deadlineTime = "25:99"),
            similarDone = List(6) { SimilarTask("x", "S", 10) },
        )
        assertThat(EnrichRoute.requestProblems(bad)).containsExactly(
            "text",
            "language",
            "now",
            "timeZone",
            "estimateScale",
            "extracted.estimate",
            "extracted.deadlineTime",
            "similarDone",
        )
        assertThat(EnrichRoute.requestProblems(request.copy(text = "a".repeat(EnrichRoute.MAX_TEXT_LENGTH + 1)))).containsExactly("text")
        assertThat(EnrichRoute.requestProblems(request.copy(today = "2026-10-09"))).containsExactly("today")
        assertThat(EnrichRoute.requestProblems(request.copy(similarDone = listOf(SimilarTask("ok", "M", -5)))))
            .containsExactly("similarDone.item")
    }

    @Test
    fun `route defaults follow the tech plan`() {
        assertThat(EnrichRoute.PATH).isEqualTo(AiRoute.ENRICH.path)
        assertThat(EnrichRoute.defaultSettings)
            .isEqualTo(RouteSettings("anthropic/claude-opus-5.5", "low", 4_096, zeroDataRetention = true))
        assertThat(AiRoute.SPLIT.defaultSettings.effort).isEqualTo("medium")
        assertThat(AiRoute.SUMMARIZE_SOURCE.defaultSettings.effort).isEqualTo("medium")
        assertThat(AiRoute.entries.filter { it.defaultSettings.effort == "low" })
            .containsExactly(AiRoute.ENRICH, AiRoute.CLASSIFY, AiRoute.NEXT_STEP, AiRoute.SIMILAR)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown effort is rejected`() {
        RouteSettings(effort = "turbo")
    }

    @Test
    fun `wire format omits nulls and ignores unknown keys`() {
        val encoded = AiJson.wire.encodeToString(EnrichResponse.serializer(), EnrichResponse(estimate = "S", estimateConfidence = 0.9))
        assertThat(encoded).isEqualTo("""{"estimate":"S","estimateConfidence":0.9}""")
        val decoded = AiJson.wire.decodeFromString(EnrichResponse.serializer(), """{"estimate":"M","future":"field"}""")
        assertThat(decoded).isEqualTo(EnrichResponse(estimate = "M"))
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val PROMPT_FINGERPRINT = "669ee8677ca3e3e3"
    }
}
