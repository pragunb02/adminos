package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.ai.eval.AiEvalRecords
import dev.adminos.api.infrastructure.ai.OllamaProvider
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/** What the bake-off command was asked to do. */
data class BakeOffArgs(
    val models: List<String>,
    val baseUrl: URI,
    val timeout: Duration,
    /** CPU threads per request (OLLAMA_NUM_THREAD); null lets Ollama choose. */
    val numThread: Int?,
    val outDir: File,
    /** When set, passing models' eval records are added to this `evals.json`. */
    val evalRecords: File?
) {
    init {
        require(models.isNotEmpty()) { "name at least one model: -Pmodels=qwen3:4b,gemma3:4b" }
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:11434"

        fun parse(args: Array<String>, env: (String) -> String?): BakeOffArgs {
            fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) ?: throw IllegalArgumentException("$name needs a value") }
            val models = arg("--models")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val timeout = (env("OLLAMA_TIMEOUT_SECONDS")?.toLongOrNull() ?: 120L).also { require(it in 1..600) { "OLLAMA_TIMEOUT_SECONDS is 1..600" } }
            return BakeOffArgs(
                models = models,
                baseUrl = URI(arg("--base-url") ?: env("OLLAMA_BASE_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL),
                timeout = Duration.ofSeconds(timeout),
                numThread = env("OLLAMA_NUM_THREAD")?.let { it.toIntOrNull()?.takeIf { n -> n in 1..256 } ?: throw IllegalArgumentException("OLLAMA_NUM_THREAD is 1..256") },
                outDir = File(arg("--out") ?: "build/reports/ai-bakeoff"),
                evalRecords = arg("--write-eval-records")?.let(::File)
            )
        }
    }
}

/**
 * `./gradlew -q aiBakeOff -Pmodels=qwen3:4b,gemma3:4b` (AOS-703): runs each local model (Ollama at
 * `OLLAMA_BASE_URL`, default `http://127.0.0.1:11434`; `OLLAMA_NUM_THREAD` limits CPU threads on a busy machine) through the categorisation golden set via the AI port,
 * and prints how it compares with the rules alone and with the recorded Claude answers. Only the golden
 * set's synthetic merchant names are sent, and only to the local server. Writes each model's answers (CSV)
 * under `build/reports/ai-bakeoff/`. With `-PwriteEvalRecord`, passing models get an eval record in
 * `src/main/resources/ai/evals.json`, which is what lets `AI_CATEGORIZATION_PROVIDER=ollama` use them.
 */
fun main(args: Array<String>) = bakeOff(args)

/** The bake-off command itself (separate from [main], which shares its package with the categorisation report). */
fun bakeOff(args: Array<String>, env: (String) -> String? = System::getenv) {
    val options = BakeOffArgs.parse(args, env)
    val bakeOff = CategorizationBakeOff()
    // Same rules as the app's OLLAMA_BASE_URL (AOS-702/703 reviews): no credentials, https unless truly local.
    val url = options.baseUrl
    require(url.rawUserInfo == null) { "--base-url must not contain credentials" }
    require(url.scheme == "https" || (url.scheme == "http" && url.host != null && dev.adminos.api.config.AiConfig.isPrivateHost(url.host))) {
        "--base-url must use https unless it is localhost, a loopback IP or the compose service ollama"
    }
    val provider = OllamaProvider(options.baseUrl, options.timeout, options.numThread)
    val runs = runBlocking {
        bakeOff.baselines() + options.models.map { model ->
            println("Asking $model at ${options.baseUrl} …")
            bakeOff.run(provider, model)
        }
    }
    println()
    println("Categorization bake-off: golden set (${runs.first().report.total} rows, synthetic); gate = rules baseline, no rule-decided row changed, ≥ 90% valid answers")
    println(CategorizationBakeOff.table(runs))

    options.outDir.mkdirs()
    for ((model, run) in options.models.zip(runs.drop(2))) {
        val file = File(options.outDir, model.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".csv")
        file.writeText(recording(model, run))
        println("Answers of $model: ${file.path}")
    }
    val target = options.evalRecords ?: return
    val today = LocalDate.now(ZoneOffset.UTC).toString()
    var records = AiEvalRecords.parse(target.readText())
    for ((model, run) in options.models.zip(runs.drop(2))) {
        if (!run.passes) {
            println("Not recorded: $model is below the rules baseline")
            continue
        }
        records = records.with(bakeOff.record(CategorizationBakeOff.key(model), run, today))
        println("Eval record written for $model (commit ${target.path} to allow it)")
    }
    target.writeText(records.toJson())
}

/** A model's answers in the `ai-recorded.csv` format, so they can be inspected or replayed. */
internal fun recording(model: String, run: BakeOffRun): String =
    "# AOS-703 bake-off answers from the local model $model (Ollama). Only these golden-set merchant names were sent, locally.\n" +
        "merchant,category,confidence,reason\n" +
        run.answers.toSortedMap().entries.joinToString("") { (m, a) -> "$m,${a.category.name},${a.confidence},${a.reason.replace(',', ';').replace('\n', ' ')}\n" }
