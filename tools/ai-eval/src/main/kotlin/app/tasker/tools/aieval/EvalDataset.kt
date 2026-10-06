package app.tasker.tools.aieval

import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EstimateScale
import app.tasker.core.ai.contract.ExtractedFields
import app.tasker.core.ai.contract.SimilarTask
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Shared context of every case: the eval runs as if the user's clock showed [now] (tech plan §17.6). */
@Serializable
data class EvalMeta(
    val version: String,
    val status: String,
    val now: String,
    val today: String,
    val timeZone: String,
    val scale: EstimateScale,
    val notes: List<String> = emptyList(),
)

/** The validated answer the app should get. Fragments document the source text and are not scored. */
@Serializable
data class ExpectedFields(
    val estimate: String? = null,
    val deadlineDate: String? = null,
    val deadlineTime: String? = null,
    val deadlineFragment: String? = null,
    val planDate: String? = null,
    val planDateFragment: String? = null,
)

@Serializable
data class EvalCase(
    val id: String,
    val language: String,
    val text: String,
    val expected: ExpectedFields,
    val extracted: ExtractedFields = ExtractedFields(),
    val similarDone: List<SimilarTask> = emptyList(),
    /** Why the case is tricky, for the reviewer. */
    val note: String? = null,
) {
    fun request(meta: EvalMeta): EnrichRequest = EnrichRequest(
        text = text,
        language = language,
        now = meta.now,
        today = meta.today,
        timeZone = meta.timeZone,
        estimateScale = meta.scale,
        extracted = extracted,
        similarDone = similarDone,
    )
}

/**
 * JSONL dataset: the first non-blank line is `{"_meta": {...}}` ([EvalMeta]), every further non-blank line
 * one [EvalCase]. Unknown keys are errors, so a typo in a label cannot silently drop it.
 */
class EvalDataset(val meta: EvalMeta, val cases: List<EvalCase>) {

    fun select(language: String?, limit: Int?): List<EvalCase> = cases
        .filter { language == null || it.language == language }
        .let { if (limit == null) it else it.take(limit) }

    companion object {
        private val json = Json { ignoreUnknownKeys = false }

        fun load(path: Path): EvalDataset = parse(Files.readAllLines(path))

        fun parse(lines: List<String>): EvalDataset {
            val content = lines.withIndex().filter { it.value.isNotBlank() }
            require(content.isNotEmpty()) { "The dataset is empty" }
            val header = json.parseToJsonElement(content.first().value).jsonObject
            val meta = json.decodeFromJsonElement(
                EvalMeta.serializer(),
                requireNotNull(header["_meta"]) {
                    "Line 1 must be {\"_meta\": ...}"
                },
            )
            val cases = content.drop(1).map { (index, line) ->
                try {
                    json.decodeFromString(EvalCase.serializer(), line)
                } catch (e: SerializationException) {
                    throw IllegalArgumentException("Line ${index + 1}: ${e.message}", e)
                }
            }
            val duplicates = cases.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "Duplicate case ids: $duplicates" }
            return EvalDataset(meta, cases)
        }
    }
}
