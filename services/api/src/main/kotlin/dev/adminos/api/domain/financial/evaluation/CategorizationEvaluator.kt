package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionType
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

/** A category with an optional subcategory: what the categorizer answers, and what a person expects (AOS-511). */
@Serializable
data class Label(val category: TransactionCategory, val subcategory: String? = null) {
    init {
        require(subcategory == null || subcategory.isNotBlank()) { "A subcategory is absent or non-blank" }
    }
}

/** What the categorizer sees for one golden row: the bank's text, never an amount or an identity. */
data class CaseInput(
    val merchantRaw: String?,
    val upiVpa: String?,
    val paymentMethod: PaymentMethod?,
    val type: TransactionType
) {
    init {
        require(!merchantRaw.isNullOrBlank() || !upiVpa.isNullOrBlank()) { "A case has a narration or a UPI ID" }
        require(type == TransactionType.DEBIT || type == TransactionType.CREDIT) { "A case is a debit or a credit" }
    }
}

/** One labelled row of the golden set. [tags] group hard cases ("p2p", "gateway", "refund"…) in the report. */
data class LabelledCase(val id: String, val input: CaseInput, val expected: Label, val tags: Set<String> = emptySet()) {
    init {
        require(id.isNotBlank()) { "A case has an id" }
    }
}

/** The categorizer's answer for a case, and which link of the chain gave it ("rules", "remembered", "ai"…). */
data class Prediction(val label: Label, val source: String)

data class Outcome(val case: LabelledCase, val predicted: Prediction) {
    val correct: Boolean get() = case.expected.category == predicted.label.category
}

@Serializable
data class CategoryScore(
    val category: TransactionCategory,
    /** Rows whose expected category is this one. */
    val support: Int,
    /** Rows the categorizer put in this category. */
    val predicted: Int,
    val truePositives: Int,
    val precision: Double,
    val recall: Double,
    val f1: Double
)

@Serializable
data class Confusion(val expected: TransactionCategory, val predicted: TransactionCategory, val count: Int, val ids: List<String>)

@Serializable
data class Miss(
    val id: String,
    val merchantRaw: String?,
    val upiVpa: String?,
    val expected: Label,
    val predicted: Label,
    val source: String,
    val tags: List<String>
)

@Serializable
data class TagScore(val tag: String, val total: Int, val correct: Int, val accuracy: Double)

/**
 * How well a categorizer did on a labelled set (AOS-511, D-61). Category is the primary score;
 * the subcategory is scored only on rows that expect one. Coverage is the share not left as Other —
 * reported, not gated, because guessing more is not the same as being right.
 */
@Serializable
data class EvaluationReport(
    val total: Int,
    val correct: Int,
    val accuracy: Double,
    val coverage: Double,
    val macroF1: Double,
    val subcategoryScored: Int,
    val subcategoryCorrect: Int,
    val subcategoryAccuracy: Double,
    val perCategory: List<CategoryScore>,
    val confusions: List<Confusion>,
    val bySource: Map<String, Int>,
    val byTag: List<TagScore>,
    val misses: List<Miss>
) {
    fun scoreOf(category: TransactionCategory): CategoryScore? = perCategory.firstOrNull { it.category == category }
}

/** Pure scoring of outcomes: no I/O, deterministic order, ratios rounded to 4 places. */
object CategorizationEvaluator {

    fun evaluate(outcomes: List<Outcome>): EvaluationReport {
        require(outcomes.isNotEmpty()) { "Nothing to evaluate" }
        val ids = outcomes.map { it.case.id }
        require(ids.size == ids.toSet().size) { "Case ids must be unique" }

        val correct = outcomes.count { it.correct }
        val covered = outcomes.count { it.predicted.label.category != TransactionCategory.OTHER }
        val scoredSub = outcomes.filter { it.case.expected.subcategory != null }
        val subCorrect = scoredSub.count { it.correct && it.predicted.label.subcategory == it.case.expected.subcategory }

        val perCategory = TransactionCategory.entries.mapNotNull { c -> categoryScore(c, outcomes) }
        val withSupport = perCategory.filter { it.support > 0 }

        val confusions = outcomes.filterNot { it.correct }
            .groupBy { it.case.expected.category to it.predicted.label.category }
            .map { (pair, os) -> Confusion(pair.first, pair.second, os.size, os.map { it.case.id }.sorted()) }
            .sortedWith(compareByDescending<Confusion> { it.count }.thenBy { it.expected }.thenBy { it.predicted })

        val byTag = outcomes.flatMap { o -> o.case.tags.map { it to o } }
            .groupBy({ it.first }, { it.second })
            .map { (tag, os) -> TagScore(tag, os.size, os.count { it.correct }, ratio(os.count { it.correct }, os.size)) }
            .sortedBy { it.tag }

        val misses = outcomes.filterNot { it.correct }.sortedBy { it.case.id }.map {
            Miss(it.case.id, it.case.input.merchantRaw, it.case.input.upiVpa, it.case.expected, it.predicted.label,
                it.predicted.source, it.case.tags.sorted())
        }

        return EvaluationReport(
            total = outcomes.size,
            correct = correct,
            accuracy = ratio(correct, outcomes.size),
            coverage = ratio(covered, outcomes.size),
            macroF1 = round(withSupport.sumOf { it.f1 } / withSupport.size.coerceAtLeast(1)),
            subcategoryScored = scoredSub.size,
            subcategoryCorrect = subCorrect,
            subcategoryAccuracy = ratio(subCorrect, scoredSub.size),
            perCategory = perCategory,
            confusions = confusions,
            bySource = outcomes.groupingBy { it.predicted.source }.eachCount().toSortedMap(),
            byTag = byTag,
            misses = misses
        )
    }

    /** Null when the category is neither expected nor predicted anywhere. */
    private fun categoryScore(c: TransactionCategory, outcomes: List<Outcome>): CategoryScore? {
        val support = outcomes.count { it.case.expected.category == c }
        val predicted = outcomes.count { it.predicted.label.category == c }
        if (support == 0 && predicted == 0) return null
        val tp = outcomes.count { it.case.expected.category == c && it.predicted.label.category == c }
        val precision = ratio(tp, predicted)
        val recall = ratio(tp, support)
        val f1 = if (precision + recall == 0.0) 0.0 else round(2 * precision * recall / (precision + recall))
        return CategoryScore(c, support, predicted, tp, precision, recall, f1)
    }

    /** [part] / [whole], 0 when there is no whole. */
    private fun ratio(part: Int, whole: Int): Double = if (whole == 0) 0.0 else round(part.toDouble() / whole)

    private fun round(x: Double): Double = BigDecimal.valueOf(x).setScale(4, RoundingMode.HALF_UP).toDouble()
}
