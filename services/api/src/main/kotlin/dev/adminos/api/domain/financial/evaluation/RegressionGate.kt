package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.financial.TransactionCategory
import kotlinx.serialization.Serializable

/**
 * The checked-in floor for a categorizer's scores (AOS-511, D-61). A change may not score below it;
 * a deliberate improvement rewrites it (`./gradlew -q categorizationReport -PwriteBaseline`).
 */
@Serializable
data class Baseline(
    /** Which labeller the numbers belong to, e.g. "rules" or "rules+ai". */
    val subject: String,
    val goldenRows: Int,
    val accuracy: Double,
    val subcategoryAccuracy: Double,
    /** Category name → F1. */
    val f1: Map<String, Double>
) {
    init {
        require(subject.isNotBlank()) { "A baseline names its subject" }
        require(goldenRows > 0) { "A baseline counts its rows" }
        require(f1.keys.all { k -> TransactionCategory.entries.any { it.name == k } }) { "Baseline F1 keys are category names" }
    }

    companion object {
        fun of(subject: String, report: EvaluationReport) = Baseline(
            subject = subject,
            goldenRows = report.total,
            accuracy = report.accuracy,
            subcategoryAccuracy = report.subcategoryAccuracy,
            f1 = report.perCategory.filter { it.support > 0 }.associate { it.category.name to it.f1 }.toSortedMap()
        )
    }
}

/** One score that fell below its baseline. */
data class Regression(val metric: String, val baseline: Double, val actual: Double) {
    override fun toString() = "$metric dropped from $baseline to $actual"
}

/** Compares a report with its baseline. Pure; the test turns the result into a failure message. */
object RegressionGate {
    /** Scores are rounded to 4 places, so anything smaller than this is noise, not a drop. */
    private const val TOLERANCE = 1e-9

    fun regressions(report: EvaluationReport, baseline: Baseline): List<Regression> {
        val drops = mutableListOf<Regression>()
        if (report.accuracy < baseline.accuracy - TOLERANCE) drops += Regression("overall accuracy", baseline.accuracy, report.accuracy)
        if (report.subcategoryAccuracy < baseline.subcategoryAccuracy - TOLERANCE) {
            drops += Regression("subcategory accuracy", baseline.subcategoryAccuracy, report.subcategoryAccuracy)
        }
        for ((name, floor) in baseline.f1.toSortedMap()) {
            val actual = report.scoreOf(TransactionCategory.valueOf(name))?.f1 ?: 0.0
            if (actual < floor - TOLERANCE) drops += Regression("$name F1", floor, actual)
        }
        return drops
    }

    /** Scores now above the baseline: worth recording, so the floor keeps up with the code. */
    fun improvements(report: EvaluationReport, baseline: Baseline): List<String> {
        val ups = mutableListOf<String>()
        if (report.accuracy > baseline.accuracy + TOLERANCE) ups += "overall accuracy ${baseline.accuracy} → ${report.accuracy}"
        if (report.subcategoryAccuracy > baseline.subcategoryAccuracy + TOLERANCE) {
            ups += "subcategory accuracy ${baseline.subcategoryAccuracy} → ${report.subcategoryAccuracy}"
        }
        for (s in report.perCategory.filter { it.support > 0 }) {
            val floor = baseline.f1[s.category.name] ?: continue
            if (s.f1 > floor + TOLERANCE) ups += "${s.category} F1 $floor → ${s.f1}"
        }
        return ups
    }
}
