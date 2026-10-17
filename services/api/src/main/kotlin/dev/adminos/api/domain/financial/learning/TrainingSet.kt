package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.CategoryCorrectionService
import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.MerchantCatalogue
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature

/** What a user's model would learn from: their payees (one row each) plus the synthetic prior. */
data class TrainingSet(val examples: List<TrainingExample>) {
    /** The user's own corrected payees: the cold-start count. */
    val corrections: Int get() = examples.count { it.origin.isUsers }
    val categories: Set<TransactionCategory> get() = examples.map { it.label }.toSet()
}

/**
 * Builds a user's training rows from their transactions (AOS-707). Pure.
 * - **Learned from:** hand corrections and categories picked at entry (`user`), remembered re-labels (`remembered`),
 *   and real categories the built-in rules gave (`rules`, not "left as Other").
 * - **Never learned from** (no feedback loop): labels from a model (`learned`, `ai`), links (`linked`), rows "left as
 *   Other" by the rules, transfers, income and reversals.
 * - **One row per payee** (the cleaned name, else the UPI ID), so a weekly chai stall doesn't outweigh everything:
 *   the user's own choice wins over a rule, then the latest.
 */
object TrainingSetBuilder {
    private const val USER_KEY_PREFIX = "user:"
    private val NATURES = setOf(TransactionNature.SPEND, TransactionNature.REFUND)

    fun build(transactions: List<Transaction>, prior: List<TrainingExample>): TrainingSet {
        val users = transactions.mapNotNull { t -> exampleOf(t)?.let { it to t } }
            .groupBy { (example, _) -> example.key }
            .map { (_, rows) -> rows.maxWith(compareBy<Pair<TrainingExample, Transaction>> { it.first.origin.isUsers }.thenBy { it.second.updatedAt }).first }
            .sortedBy { it.key }
        return TrainingSet(users + prior)
    }

    /** The training row for one transaction, or null when it must not be learned from. */
    fun exampleOf(t: Transaction): TrainingExample? {
        if (t.nature !in NATURES || t.category == TransactionCategory.TRANSFER) return null
        val origin = when (t.categorySource) {
            CategoryCorrectionService.SOURCE_USER -> ExampleOrigin.CORRECTION
            CategorizationService.SOURCE_REMEMBERED -> ExampleOrigin.REMEMBERED
            LearnedCategorizer.SOURCE_RULES -> ExampleOrigin.RULES.takeIf { t.category != TransactionCategory.OTHER || t.subcategory != null }
            else -> null
        } ?: return null
        val key = payeeKey(t) ?: return null
        return TrainingExample(USER_KEY_PREFIX + key, CategoryInput.of(t), t.category, origin)
    }

    /** The payee's cleaned name in matching form, else its UPI ID; null when neither is known. */
    private fun payeeKey(t: Transaction): String? =
        t.merchantName?.let { MerchantCatalogue.matchForm(it) }?.takeIf { it.isNotEmpty() }
            ?: t.upiVpa?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}
