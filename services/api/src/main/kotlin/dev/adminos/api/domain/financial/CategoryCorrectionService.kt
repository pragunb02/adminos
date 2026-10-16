package dev.adminos.api.domain.financial

import java.time.Instant

/** The result of a user correction: the corrected transaction and how many past ones were re-labelled. */
data class CategoryCorrection(val transaction: Transaction, val remembered: Boolean, val relabelled: Int)

/**
 * A user's category correction (AOS-210, D-41). With [remember], it becomes the payee's rule:
 * future transactions get it from [CategorizationService], and past spending and refunds from the
 * same payee are re-labelled now — except those the user set by hand. One unit of work.
 */
class CategoryCorrectionService(
    private val rules: CategoryRuleRepository,
    private val transactions: TransactionRepository,
    private val unitOfWork: suspend (suspend () -> Unit) -> Unit = { it() }
) {
    suspend fun correct(txn: Transaction, category: TransactionCategory, subcategory: String?, remember: Boolean = true): CategoryCorrection {
        lateinit var updated: Transaction
        var relabelled = 0
        val key = if (remember) CategoryRule.keyOf(txn) else null
        unitOfWork {
            updated = transactions.update(txn.copy(category = category, subcategory = subcategory, categorySource = SOURCE_USER))
            if (key != null) {
                val (match, value) = key
                rules.upsert(CategoryRule(userId = txn.userId, match = match, value = value, category = category,
                    subcategory = subcategory, updatedAt = Instant.now()))
                transactions.findByPayee(txn.userId, match, value)
                    .filter { it.id != txn.id && it.categorySource != SOURCE_USER && it.nature in RELABELLED }
                    .filter { it.category != category || it.subcategory != subcategory }
                    .forEach {
                        transactions.update(it.copy(category = category, subcategory = subcategory, categorySource = CategorizationService.SOURCE_REMEMBERED))
                        relabelled++
                    }
            }
        }
        return CategoryCorrection(updated, remembered = key != null, relabelled = relabelled)
    }

    companion object {
        const val SOURCE_USER = "user"
        /** Transfers and reversals keep their own category; a refund follows its payee like the spend did. */
        private val RELABELLED = setOf(TransactionNature.SPEND, TransactionNature.REFUND)
    }
}
