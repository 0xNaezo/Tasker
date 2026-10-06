package app.tasker.tools.aieval

import app.tasker.core.ai.contract.EnrichRequest
import app.tasker.core.ai.contract.EnrichResponse
import app.tasker.core.ai.contract.EnrichRoute
import app.tasker.core.ai.contract.RouteResult
import app.tasker.core.ai.contract.RouteSettings
import app.tasker.core.ai.openrouter.OpenRouterRouteRunner
import app.tasker.core.ai.openrouter.openRouterTimeouts
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.system.exitProcess
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking

/**
 * Manual quality eval of `/v1/enrich` (tech plan §17.6). Every case is a real, paid model call through OpenRouter,
 * so it runs by hand when the prompt or the model changes, never in CI:
 *
 * ```
 * OPENROUTER_API_KEY=... ./gradlew :tools:ai-eval:run --args="--limit 20"
 * ```
 */
fun main(args: Array<String>) {
    val options = try {
        EvalOptions.parse(args.toList())
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println(EvalOptions.USAGE)
        exitProcess(EXIT_USAGE)
    }
    val apiKey = System.getenv("OPENROUTER_API_KEY")?.trim()?.takeIf { it.isNotEmpty() } ?: run {
        System.err.println("OPENROUTER_API_KEY is not set. The eval makes paid model calls through OpenRouter.")
        exitProcess(EXIT_USAGE)
    }
    val dataset = EvalDataset.load(options.dataset)
    val cases = dataset.select(options.language, options.limit)
    println("Running ${cases.size} cases of ${dataset.meta.version} with ${options.settings}, ${options.concurrency} at a time")

    val http = HttpClient(OkHttp) { openRouterTimeouts() }
    val outcomes = try {
        val runner = OpenRouterRouteRunner(http, apiKey, options.settings)
        // Each worker thread waits for its own call; the runner's retries and timeouts apply per case.
        EvalRunner({ request -> runBlocking { runner.enrich(request) } }, options.concurrency).run(dataset.meta, cases) { done, outcome ->
            println("[$done/${cases.size}] ${outcome.case.id} ${status(outcome.result)} ${outcome.latencyMs} ms")
        }
    } finally {
        http.close()
    }

    val date = LocalDate.ofInstant(SystemTimeSource.clock.instant(), ZoneOffset.UTC)
    val report = EvalReport.render(dataset.meta, options.settings, date, EvalSummary(outcomes))
    val file = reportFile(options.outDir, date)
    Files.createDirectories(options.outDir)
    Files.writeString(file, report)
    println("Report written to $file")
}

private const val EXIT_USAGE = 2

private fun status(result: RouteResult<EnrichResponse>): String = when (result) {
    is RouteResult.Success -> "ok"
    is RouteResult.Refused -> "refused"
    is RouteResult.Failed -> "failed:${result.kind.name.lowercase()}"
}

/** `report-<date>.md`, or `report-<date>-2.md` and so on when the day already has a report. */
fun reportFile(dir: Path, date: LocalDate): Path = generateSequence(1) { it + 1 }
    .map { index -> dir.resolve(if (index == 1) "report-$date.md" else "report-$date-$index.md") }
    .first { !Files.exists(it) }

/** Runs cases on [concurrency] threads and returns their outcomes in dataset order. */
class EvalRunner(
    private val enrich: (EnrichRequest) -> RouteResult<EnrichResponse>,
    private val concurrency: Int,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    fun run(meta: EvalMeta, cases: List<EvalCase>, progress: (done: Int, outcome: EvalOutcome) -> Unit = { _, _ -> }): List<EvalOutcome> {
        val pool = Executors.newFixedThreadPool(concurrency)
        try {
            val futures = cases.map { case -> pool.submit(Callable { runCase(meta, case) }) }
            return futures.mapIndexed { index, future -> future.get().also { progress(index + 1, it) } }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun runCase(meta: EvalMeta, case: EvalCase): EvalOutcome {
        val started = timeSource.markNow()
        val result = enrich(case.request(meta))
        return EvalOutcome(case, result, started.elapsedNow().inWholeMilliseconds)
    }
}

data class EvalOptions(
    val dataset: Path = Path.of("docs/ai-eval/enrich-v1.jsonl"),
    val outDir: Path = Path.of("docs/ai-eval"),
    val settings: RouteSettings = EnrichRoute.defaultSettings,
    val concurrency: Int = DEFAULT_CONCURRENCY,
    val limit: Int? = null,
    val language: String? = null,
) {
    companion object {
        const val DEFAULT_CONCURRENCY = 4
        private const val MAX_CONCURRENCY = 16

        val USAGE = """
            Usage: ./gradlew :tools:ai-eval:run --args="[options]"
              --dataset <file>      default docs/ai-eval/enrich-v1.jsonl
              --out <dir>           default docs/ai-eval
              --model <id>          default ${EnrichRoute.defaultSettings.model}
              --effort <level>      default ${EnrichRoute.defaultSettings.effort}; one of ${RouteSettings.EFFORTS}
              --max-tokens <n>      default ${EnrichRoute.defaultSettings.maxTokens}
              --no-zdr              allow endpoints without zero data retention
              --concurrency <n>     parallel calls, default $DEFAULT_CONCURRENCY
              --language <ru|uk|en> only cases of one language
              --limit <n>           only the first n cases
        """.trimIndent()

        fun parse(args: List<String>): EvalOptions {
            var options = EvalOptions()
            var settings = options.settings
            val queue = ArrayDeque(args)
            while (queue.isNotEmpty()) {
                when (val flag = queue.removeFirst()) {
                    "--dataset" -> options = options.copy(dataset = Path.of(value(queue, flag)))
                    "--out" -> options = options.copy(outDir = Path.of(value(queue, flag)))
                    "--model" -> settings = settings.copy(model = value(queue, flag))
                    "--effort" -> settings = settings.copy(effort = value(queue, flag))
                    "--max-tokens" -> settings = settings.copy(maxTokens = number(queue, flag).toLong())
                    "--no-zdr" -> settings = settings.copy(zeroDataRetention = false)
                    "--concurrency" -> options = options.copy(concurrency = number(queue, flag))
                    "--language" -> options = options.copy(language = value(queue, flag))
                    "--limit" -> options = options.copy(limit = number(queue, flag))
                    else -> throw IllegalArgumentException("Unknown option: $flag")
                }
            }
            require(options.concurrency in 1..MAX_CONCURRENCY) { "--concurrency must be in 1..$MAX_CONCURRENCY" }
            require(options.limit == null || options.limit > 0) { "--limit must be positive" }
            require(options.language == null || options.language in EnrichRoute.LANGUAGES) {
                "--language must be one of ${EnrichRoute.LANGUAGES}"
            }
            return options.copy(settings = settings)
        }

        private fun value(queue: ArrayDeque<String>, flag: String): String =
            requireNotNull(queue.removeFirstOrNull()) { "$flag needs a value" }

        private fun number(queue: ArrayDeque<String>, flag: String): Int =
            requireNotNull(value(queue, flag).toIntOrNull()) { "$flag needs a number" }
    }
}
