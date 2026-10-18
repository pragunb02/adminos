package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.CategoryRuleRepository
import dev.adminos.api.domain.financial.MerchantCatalogue
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * When a learned guess is used (AOS-707). [applyAt] was chosen on the golden set's cross-validation so that no
 * category's precision drops below the rules' (D-61, D-67); below it, a guess of at least [suggestAt] is kept
 * as a suggestion for the Review queue (AOS-708).
 */
data class LearnedPolicy(
    val applyAt: Double = 0.8,
    val suggestAt: Double = 0.4,
    /** Fewer of the user's own corrected payees than this: cold start, the model is off. */
    val minCorrections: Int = 20,
    /** A model applies guesses only if, on held-out corrections it was sure of, it was right this often… */
    val minHeldOutPrecision: Double = 0.9,
    /** …over at least this many of them; otherwise it only suggests. */
    val minHeldOutSure: Int = 5,
    /** The newest rows learned from: a year, at most [maxTrainingRows]. */
    val trainingWindow: Duration = Duration.ofDays(365),
    val maxTrainingRows: Int = 5_000,
    /** After training, Other rows this recent are looked at again, at most [backfillLimit] per user. */
    val backfillWindow: Duration = Duration.ofDays(30),
    val backfillLimit: Int = 500
) {
    init {
        require(suggestAt in 0.0..1.0 && applyAt in 0.0..1.0 && suggestAt <= applyAt) { "0 ≤ suggestAt ≤ applyAt ≤ 1" }
        require(minCorrections >= 1) { "Cold start needs at least one correction" }
        require(minHeldOutPrecision in 0.0..1.0 && minHeldOutSure >= 1) { "The held-out check is a ratio over at least one row" }
        require(!trainingWindow.isNegative && !trainingWindow.isZero && maxTrainingRows >= 1) { "Training reads some rows" }
        require(!backfillWindow.isNegative && backfillLimit >= 0) { "The backfill window is not negative" }
    }
}

/** What the learned step did with one transaction. Counted by the job; never logged with names or amounts. */
enum class LearnedOutcome {
    /** Sure enough: the category is set, with source `learned`. */
    APPLIED,
    /** Kept as Other; the guess is in `metadata.learned` for the Review queue. */
    SUGGESTED,
    /** The guess was Other, or too unsure even to suggest: nothing is written. */
    UNSURE,
    /** Not a candidate: a rule, a person, a link or another model decided it, or it isn't spending. */
    NOT_ELIGIBLE,
    /** The user has no usable model (cold start, off, or a damaged model). */
    NO_MODEL
}

/** A decision on one transaction: [transaction] is the one to store (unchanged unless applied or suggested). */
data class LearnedDecision(val transaction: Transaction, val outcome: LearnedOutcome)

/**
 * The learned categoriser's place in the chain (AOS-707, D-67): rules → remembered → **learned** → AI → Other.
 * - **It never overrides a rule:** only a transaction the built-in rules left as plain Other (source `rules`, no
 *   subcategory, not a catalogue brand, no remembered rule for the payee) is a candidate; spending and refunds only.
 * - **Sure** guesses (≥ [LearnedPolicy.applyAt], and only from a model in [ModelMode.APPLY]) set the category with
 *   source `learned`; **unsure** ones (≥ [LearnedPolicy.suggestAt]) stay Other with the guess in `metadata.learned`.
 *   The AI fallback (AOS-512) still looks at anything left as Other with source `rules`.
 * - It runs as the last pipeline step (local, microseconds) and again over recent Other rows after each training.
 */
class LearnedCategorizer(
    private val store: CategoryModelStore,
    private val rules: CategoryRuleRepository,
    private val transactions: TransactionRepository,
    private val policy: LearnedPolicy = LearnedPolicy(),
    private val catalogue: MerchantCatalogue = MerchantCatalogue.DEFAULT,
    private val clock: Clock = Clock.systemUTC()
) {
    /** Checks on the transaction alone (no I/O). */
    fun isCandidate(t: Transaction): Boolean = isRuleLeftOther(t, catalogue)

    /** The pipeline's call: the transaction to store, with a learned category or suggestion when there is one. */
    suspend fun categorize(t: Transaction): LearnedDecision {
        if (!isCandidate(t) || hasRememberedRule(t)) return LearnedDecision(t, LearnedOutcome.NOT_ELIGIBLE)
        return when (val loaded = store.load(t.userId)) {
            is LoadedModel.Ready -> decide(t, loaded, policy, clock)
            LoadedModel.None, is LoadedModel.Corrupt -> LearnedDecision(t, LearnedOutcome.NO_MODEL)
        }
    }

    /**
     * Looks again at the user's recent Other rows with their current model, and stores what changed. Idempotent:
     * a row already holding this model's answer is skipped, and a row is re-read before writing, so a correction
     * made meanwhile is never overwritten.
     */
    suspend fun backfill(userId: UUID): Map<LearnedOutcome, Int> {
        val loaded = store.load(userId) as? LoadedModel.Ready ?: return emptyMap()
        val since = clock.instant().minus(policy.backfillWindow)
        val rows = transactions.findByUserId(userId, from = since, category = TransactionCategory.OTHER, limit = policy.backfillLimit)
        return rows.filter { isCandidate(it) && learnedModelOf(it) != loaded.modelId }
            .map { backfillOne(it, loaded) }
            .groupingBy { it }.eachCount()
    }

    private suspend fun backfillOne(t: Transaction, loaded: LoadedModel.Ready): LearnedOutcome {
        val decision = decide(t, loaded, policy, clock)
        val writes = decision.outcome == LearnedOutcome.APPLIED || decision.outcome == LearnedOutcome.SUGGESTED || learnedModelOf(t) != null
        if (!writes) return decision.outcome
        val current = transactions.findById(t.id)?.takeIf { isCandidate(it) && !hasRememberedRule(it) } ?: return LearnedOutcome.NOT_ELIGIBLE
        val fresh = decide(current, loaded, policy, clock)
        // An older model's suggestion that this model no longer stands behind is removed, so Review never shows it.
        val toStore = if (fresh.outcome == LearnedOutcome.UNSURE) withoutSuggestion(current) else fresh.transaction
        if (toStore != current) transactions.update(toStore)
        return fresh.outcome
    }

    private fun withoutSuggestion(t: Transaction): Transaction {
        val metadata = t.metadata ?: return t
        if (METADATA_KEY !in metadata) return t
        return t.copy(metadata = (metadata - METADATA_KEY).takeIf { it.isNotEmpty() }, updatedAt = clock.instant())
    }

    private suspend fun hasRememberedRule(t: Transaction): Boolean {
        val vpa = t.upiVpa?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val merchant = t.merchantName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return (vpa != null || merchant != null) && rules.findFor(t.userId, vpa, merchant) != null
    }

    companion object {
        const val SOURCE_LEARNED = "learned"
        const val SOURCE_RULES = "rules"
        const val METADATA_KEY = "learned"
        const val STATUS_APPLIED = "applied"
        const val STATUS_SUGGESTED = "suggested"
        private const val MAX_ALTERNATIVES = 2
        private val NATURES = setOf(TransactionNature.SPEND, TransactionNature.REFUND)

        /** Left as plain Other by the built-in rules: the only rows a model may label (D-67: never override a rule). */
        fun isRuleLeftOther(t: Transaction, catalogue: MerchantCatalogue = MerchantCatalogue.DEFAULT): Boolean =
            t.category == TransactionCategory.OTHER && t.categorySource == SOURCE_RULES && t.subcategory == null &&
                t.nature in NATURES && catalogue.byBrand(t.merchantName) == null &&
                !(t.merchantName.isNullOrBlank() && t.upiVpa.isNullOrBlank())

        /**
         * The pure decision for an eligible transaction and a model (also used by the golden-set evaluation, so the
         * numbers measure exactly this). The caller has checked eligibility.
         */
        fun decide(t: Transaction, loaded: LoadedModel.Ready, policy: LearnedPolicy, clock: Clock): LearnedDecision {
            val input = CategoryInput.of(t)
            val guess = loaded.model.predict(input)
            val apply = loaded.mode == ModelMode.APPLY && guess.confidence >= policy.applyAt
            if (guess.category == TransactionCategory.OTHER || guess.confidence < policy.suggestAt) return LearnedDecision(t, LearnedOutcome.UNSURE)
            val record = Record(guess, alternativesTo(guess, loaded.model.distribution(input)), loaded.modelId)
            return if (apply) {
                LearnedDecision(
                    mark(t, record, STATUS_APPLIED, clock).copy(category = guess.category, subcategory = null, categorySource = SOURCE_LEARNED),
                    LearnedOutcome.APPLIED
                )
            } else {
                LearnedDecision(mark(t, record, STATUS_SUGGESTED, clock), LearnedOutcome.SUGGESTED)
            }
        }

        /** The next most likely categories after the guess (for one-tap corrections in Review, AOS-708). */
        private fun alternativesTo(guess: CategoryGuess, distribution: Map<TransactionCategory, Double>): List<TransactionCategory> =
            distribution.entries.filter { it.key != guess.category && it.key != TransactionCategory.TRANSFER }
                .sortedWith(compareByDescending<Map.Entry<TransactionCategory, Double>> { it.value }.thenBy { it.key })
                .take(MAX_ALTERNATIVES).map { it.key }

        /** The model that last wrote this row's `metadata.learned`, if any. */
        fun learnedModelOf(t: Transaction): String? = (t.metadata?.get(METADATA_KEY) as? Map<*, *>)?.get("model") as? String

        private class Record(val guess: CategoryGuess, val alternatives: List<TransactionCategory>, val modelId: String)

        private fun mark(t: Transaction, r: Record, status: String, clock: Clock): Transaction {
            val record = mapOf(
                "status" to status,
                "category" to r.guess.category.name.lowercase(),
                "confidence" to Calibration.round4(r.guess.confidence),
                "cues" to r.guess.cues,
                "alternatives" to r.alternatives.map { it.name.lowercase() },
                "model" to r.modelId,
                "at" to clock.instant().toString()
            )
            return t.copy(metadata = t.metadata.orEmpty() + (METADATA_KEY to record), updatedAt = clock.instant())
        }
    }
}
