package dev.adminos.api.domain.financial.review

import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.learning.LearnedCategorizer

/** Which model made a guess: the local learned categoriser (AOS-707) or the AI fallback (AOS-512). */
enum class GuessSource(val metadataKey: String, val categorySource: String) {
    LEARNED(LearnedCategorizer.METADATA_KEY, LearnedCategorizer.SOURCE_LEARNED),
    AI("ai", "ai")
}

/**
 * A model's guess for one transaction, read back from `metadata.learned` or `metadata.ai` (AOS-708).
 * [applied] is true when the guess set the category; false when it is only a suggestion and the row is still Other.
 */
data class CategoryGuessRecord(
    val category: TransactionCategory,
    val confidence: Double,
    val source: GuessSource,
    val applied: Boolean,
    /** Words that pushed towards it (learned), strongest first. */
    val cues: List<String> = emptyList(),
    /** The model's short reason (AI). */
    val reason: String? = null,
    /** The next most likely categories (learned), for one-tap corrections. */
    val alternatives: List<TransactionCategory> = emptyList()
) {
    init {
        require(confidence in 0.0..1.0) { "A confidence is a probability" }
        require(category != TransactionCategory.OTHER && category != TransactionCategory.TRANSFER) { "A guess worth checking names a real category" }
    }

    /** How unsure the model was: 1 − confidence (least-confidence sampling). */
    val uncertainty: Double get() = 1.0 - confidence
}

/**
 * One "check this category" question for the Review queue (AOS-708): a transaction, the guess to confirm or
 * correct, and its [priority] = uncertainty × amount in rupees, so a big unsure spend comes before a small one.
 */
data class CategoryQuestion(val transaction: Transaction, val guess: CategoryGuessRecord) {
    val priority: Double get() = guess.uncertainty * transaction.amount.toDouble()

    companion object {
        private val NATURES = setOf(TransactionNature.SPEND, TransactionNature.REFUND)
        private const val STATUS_APPLIED = "applied"
        private const val STATUS_SUGGESTED = "suggested"

        /**
         * The question for [t], or null when there is nothing to ask:
         * - a model's guess that set the category, still as the model left it (a person hasn't touched it), or
         * - an unsure guess on a row still left as Other by the rules.
         * Of two guesses (learned and AI), the more confident one is asked. Damaged metadata is ignored, never an error.
         */
        fun of(t: Transaction): CategoryQuestion? {
            if (t.nature !in NATURES) return null
            return GuessSource.entries.mapNotNull { source -> readGuess(t, source)?.takeIf { stillOpen(t, it) } }
                .maxWithOrNull(compareBy<CategoryGuessRecord> { it.confidence }.thenBy { it.source == GuessSource.LEARNED }) // a tie: the local one
                ?.let { CategoryQuestion(t, it) }
        }

        private fun stillOpen(t: Transaction, g: CategoryGuessRecord): Boolean =
            if (g.applied) t.categorySource == g.source.categorySource && t.category == g.category
            else t.category == TransactionCategory.OTHER && t.categorySource == LearnedCategorizer.SOURCE_RULES && t.subcategory == null

        private fun readGuess(t: Transaction, source: GuessSource): CategoryGuessRecord? {
            val m = t.metadata?.get(source.metadataKey) as? Map<*, *> ?: return null
            // Both models write the same statuses (AOS-512 `metadata.ai`, AOS-707 `metadata.learned`).
            val applied = when (m["status"]) {
                STATUS_APPLIED -> true
                STATUS_SUGGESTED -> false
                else -> return null // "other", "rejected" or unknown: nothing to ask
            }
            val category = categoryOf(m["category"])?.takeIf { it != TransactionCategory.OTHER && it != TransactionCategory.TRANSFER } ?: return null
            val confidence = (m["confidence"] as? Number)?.toDouble()?.takeIf { it in 0.0..1.0 } ?: return null
            return CategoryGuessRecord(
                category = category,
                confidence = confidence,
                source = source,
                applied = applied,
                cues = (m["cues"] as? List<*>).orEmpty().filterIsInstance<String>(),
                reason = (m["reason"] as? String)?.takeIf { it.isNotBlank() },
                alternatives = (m["alternatives"] as? List<*>).orEmpty().mapNotNull { categoryOf(it) }.filter { it != category && it != TransactionCategory.TRANSFER }
            )
        }

        private fun categoryOf(value: Any?): TransactionCategory? =
            (value as? String)?.let { v -> TransactionCategory.entries.firstOrNull { it.name.equals(v, ignoreCase = true) } }
    }
}
