package dev.adminos.api.domain.financial.ai

import dev.adminos.api.domain.financial.CategoryRuleRepository
import dev.adminos.api.domain.financial.MerchantCatalogue
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import java.time.Clock
import java.util.UUID

/** What the AI step did with one transaction (counted by the job; never logged with names or amounts). */
enum class AiOutcome {
    /** Recategorised: the model was sure enough ([AiCategorizationService.MIN_CONFIDENCE] or more). */
    APPLIED,
    /** Kept as Other; the model's guess is recorded for the user to confirm. */
    SUGGESTED,
    /** The model also said Other. */
    KEPT_OTHER,
    /** The model's reply was not a valid answer from the list; kept as Other. */
    REJECTED,
    /** No answer this time; tried again on a later run. */
    UNAVAILABLE,
    /** Not a candidate: not Other, decided by a person or a rule, not spending, already asked, or a known merchant. */
    NOT_ELIGIBLE,
    /** The payee may not be shown to a model (a person, or no clean business name). */
    PRIVATE
}

/**
 * The AI fallback categorizer (AOS-512, D-62), for transactions the rules left as Other:
 * - **Rules always win:** never asked when a remembered rule or a catalogue entry exists for the payee, or
 *   when the category came from anywhere but the built-in rules (a person, a rule, a transfer).
 * - **Privacy:** only [AiPrivacyGate] questions are sent (cleaned business name, payment method, category list).
 * - **Asked once per merchant:** answers (and rejections) are cached by merchant name.
 * - **Confidence:** at [minConfidence] or more the category is set with source `ai`; below it the transaction
 *   stays Other and the guess is kept in `metadata.ai` as a suggestion. Confidence and reason are stored either way.
 * - **Idempotent:** `metadata.ai` marks a transaction as done; a second run skips it.
 */
class AiCategorizationService(
    private val client: AiCategoryClient,
    private val cache: AiCategoryCacheRepository,
    private val rules: CategoryRuleRepository,
    private val transactions: TransactionRepository,
    private val model: String,
    private val catalogue: MerchantCatalogue = MerchantCatalogue.DEFAULT,
    private val clock: Clock = Clock.systemUTC(),
    private val minConfidence: Double = MIN_CONFIDENCE
) {
    init {
        require(minConfidence in 0.0..1.0) { "The confidence threshold is between 0 and 1" }
        require(model.isNotBlank()) { "The model is named" }
    }

    /** Cheap checks on the transaction alone: worth looking at further? */
    fun isCandidate(t: Transaction): Boolean =
        t.category == TransactionCategory.OTHER && t.categorySource == SOURCE_RULES && t.nature in NATURES &&
            t.metadata?.containsKey(METADATA_KEY) != true && !t.merchantName.isNullOrBlank() && catalogue.byBrand(t.merchantName) == null

    suspend fun categorize(t: Transaction): AiOutcome {
        if (!isCandidate(t)) return AiOutcome.NOT_ELIGIBLE
        if (hasRule(t)) return AiOutcome.NOT_ELIGIBLE
        val question = AiPrivacyGate.question(t) ?: return AiOutcome.PRIVATE
        val result = answerFor(t.userId, question)
        if (result is AiCategoryResult.Unavailable) return AiOutcome.UNAVAILABLE

        // The user may have corrected it while we waited: re-read, and only touch it if it is still ours to touch.
        val current = transactions.findById(t.id)?.takeIf { isCandidate(it) && !hasRule(it) } ?: return AiOutcome.NOT_ELIGIBLE
        val (updated, outcome) = apply(current, result)
        transactions.update(updated)
        return outcome
    }

    private suspend fun hasRule(t: Transaction): Boolean {
        // The same keys CategorizationService looks up (D-41).
        val vpa = t.upiVpa?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val merchant = t.merchantName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return rules.findFor(t.userId, vpa, merchant) != null
    }

    private suspend fun answerFor(userId: UUID, question: AiCategoryQuestion): AiCategoryResult {
        val key = MerchantCatalogue.matchForm(question.merchant)
        cache.find(key)?.let { return it.toResult() }
        val result = client.ask(userId, question)
        CachedAiCategory.of(key, result, model, clock.instant())?.let { cache.put(it) }
        return result
    }

    private fun apply(t: Transaction, result: AiCategoryResult): Pair<Transaction, AiOutcome> {
        val at = clock.instant().toString()
        return when (result) {
            is AiCategoryResult.Rejected -> mark(t, mapOf("status" to "rejected", "at" to at)) to AiOutcome.REJECTED
            is AiCategoryResult.Answered -> {
                val a = result.answer
                val record = mapOf("category" to a.category.name.lowercase(), "confidence" to a.confidence, "reason" to a.reason,
                    "model" to result.model, "at" to at)
                when {
                    a.category == TransactionCategory.OTHER -> mark(t, record + ("status" to "other")) to AiOutcome.KEPT_OTHER
                    a.category !in AI_ALLOWED_CATEGORIES -> mark(t, mapOf("status" to "rejected", "at" to at)) to AiOutcome.REJECTED
                    a.confidence >= minConfidence -> mark(t, record + ("status" to "applied"))
                        .copy(category = a.category, subcategory = null, categorySource = SOURCE_AI) to AiOutcome.APPLIED
                    else -> mark(t, record + ("status" to "suggested")) to AiOutcome.SUGGESTED
                }
            }
            is AiCategoryResult.Unavailable -> error("unavailable results are never applied")
        }
    }

    private fun mark(t: Transaction, ai: Map<String, Any?>) = t.copy(metadata = (t.metadata.orEmpty()) + (METADATA_KEY to ai), updatedAt = clock.instant())

    companion object {
        const val SOURCE_AI = "ai"
        const val SOURCE_RULES = "rules"
        const val METADATA_KEY = "ai"
        /** Below this the transaction stays Other and the guess is only a suggestion. */
        const val MIN_CONFIDENCE = 0.7
        /** Spending and its refunds have a payee category; income, transfers and reversals don't. */
        private val NATURES = setOf(TransactionNature.SPEND, TransactionNature.REFUND)
    }
}
