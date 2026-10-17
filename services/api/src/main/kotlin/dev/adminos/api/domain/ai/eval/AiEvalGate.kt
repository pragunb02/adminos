package dev.adminos.api.domain.ai.eval

import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiProviderKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Where an eval's model answers came from. Shown next to the feature, so a synthetic score isn't mistaken for a real one. */
@Serializable
enum class AiEvalAnswers {
    /** Hand-written, plausible answers: measures the policy (thresholds, rules-first, privacy), not the model. */
    @SerialName("recorded-synthetic") RECORDED_SYNTHETIC,

    /** Answers recorded from the real model on the golden set. */
    @SerialName("recorded-real") RECORDED_REAL,

    /** Asked live during the eval run (e.g. a local model in the AOS-703 bake-off). */
    @SerialName("live") LIVE
}

/** Exactly what an eval measured: one feature, on one provider and model, with one prompt version. */
data class AiEvalKey(val feature: AiFeature, val provider: AiProviderKind, val model: String, val promptVersion: String)

/**
 * A committed eval result (generalising D-61's baseline to every AI feature, D-67): the scores of a feature's
 * golden set for one [AiEvalKey], and the floor they must meet. Written by the feature's report task, checked
 * against a re-run by a unit test, and read at start-up by [AiEvalGate].
 */
@Serializable
data class AiEvalRecord(
    val feature: String,
    val provider: String,
    val model: String,
    val promptVersion: String,
    val answers: AiEvalAnswers,
    /** ISO date of the run. */
    val evaluatedOn: String,
    val goldenRows: Int,
    /** Metric name → score, e.g. `accuracy`, `f1.FOOD`, `ruleDecidedUnchanged`. */
    val metrics: Map<String, Double>,
    /** Metric name → the lowest acceptable score. */
    val floor: Map<String, Double>
) {
    init {
        require(AiFeature.fromKey(feature) != null) { "Unknown AI feature '$feature' in an eval record" }
        require(AiProviderKind.fromKey(provider) != null) { "Unknown AI provider '$provider' in an eval record" }
        require(model.isNotBlank() && promptVersion.isNotBlank()) { "An eval record names its model and prompt version" }
        require(goldenRows > 0) { "An eval record counts its rows" }
        require(floor.isNotEmpty()) { "An eval record has a floor" }
    }

    val key: AiEvalKey
        get() = AiEvalKey(
            AiFeature.fromKey(feature) ?: error("validated in init"),
            AiProviderKind.fromKey(provider) ?: error("validated in init"),
            model, promptVersion
        )
}

@Serializable
data class AiEvalRecords(val records: List<AiEvalRecord>) {
    init {
        val duplicates = records.groupBy { it.key }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate eval records for $duplicates" }
    }

    fun find(key: AiEvalKey): AiEvalRecord? = records.firstOrNull { it.key == key }

    /** The same records with [record] added or replacing the one for its key, sorted for a stable file. */
    fun with(record: AiEvalRecord) = AiEvalRecords(
        (records.filter { it.key != record.key } + record).sortedWith(compareBy({ it.feature }, { it.provider }, { it.model }, { it.promptVersion }))
    )

    fun toJson(): String = JSON.encodeToString(serializer(), this) + "\n"

    companion object {
        const val RESOURCE = "/ai/evals.json"
        private val JSON = Json { prettyPrint = true; encodeDefaults = true }

        fun parse(text: String): AiEvalRecords = try {
            JSON.decodeFromString(serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalStateException("AI eval records are not valid JSON", e)
        }

        fun load(resource: String = RESOURCE): AiEvalRecords =
            parse(AiEvalRecords::class.java.getResource(resource)?.readText() ?: throw IllegalStateException("AI eval records not found: $resource"))
    }
}

/** A metric below its floor. */
data class MetricDrop(val metric: String, val floor: Double, val actual: Double?) {
    override fun toString() = if (actual == null) "$metric is missing (floor $floor)" else "$metric is $actual, below its floor $floor"
}

sealed interface AiEvalVerdict {
    data class Passed(val record: AiEvalRecord) : AiEvalVerdict
    data object NotEvaluated : AiEvalVerdict
    data class BelowFloor(val record: AiEvalRecord, val drops: List<MetricDrop>) : AiEvalVerdict
}

/**
 * A feature may be enabled with a provider, model and prompt version only when a committed eval record for
 * exactly that combination meets its floor (D-67). Pure; start-up and (AOS-704) Settings call it.
 */
object AiEvalGate {
    /** Scores are stored rounded, so anything smaller is noise, not a drop (as in D-61's gate). */
    private const val TOLERANCE = 1e-9

    fun drops(metrics: Map<String, Double>, floor: Map<String, Double>): List<MetricDrop> =
        floor.toSortedMap().mapNotNull { (name, min) ->
            val actual = metrics[name]
            if (actual == null || actual.isNaN() || actual < min - TOLERANCE) MetricDrop(name, min, actual) else null
        }

    fun verdict(records: AiEvalRecords, key: AiEvalKey): AiEvalVerdict {
        val record = records.find(key) ?: return AiEvalVerdict.NotEvaluated
        val drops = drops(record.metrics, record.floor)
        return if (drops.isEmpty()) AiEvalVerdict.Passed(record) else AiEvalVerdict.BelowFloor(record, drops)
    }

    /** Throws (refusing start-up) when any of [keys] has no passing record, naming each and how to fix it. */
    fun requirePassing(records: AiEvalRecords, keys: List<AiEvalKey>) {
        val problems = keys.mapNotNull { key ->
            val where = "${key.feature.key} on ${key.provider.key} with ${key.model} (prompt ${key.promptVersion})"
            when (val v = verdict(records, key)) {
                is AiEvalVerdict.Passed -> null
                AiEvalVerdict.NotEvaluated -> "$where has no eval record"
                is AiEvalVerdict.BelowFloor -> "$where is below its floor: ${v.drops.joinToString("; ")}"
            }
        }
        check(problems.isEmpty()) {
            "AI feature not evaluated (D-67): ${problems.joinToString(". ")}. Run its eval and commit the record in " +
                "src/main/resources$RESOURCE_HINT (see docs/tickets/AOS-702.md), or turn the feature off."
        }
    }

    private const val RESOURCE_HINT = AiEvalRecords.RESOURCE
}
