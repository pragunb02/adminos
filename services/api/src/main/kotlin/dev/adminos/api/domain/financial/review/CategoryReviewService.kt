package dev.adminos.api.domain.financial.review

import dev.adminos.api.domain.audit.ActorType
import dev.adminos.api.domain.audit.AuditActions
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.financial.CategoryCorrection
import dev.adminos.api.domain.financial.CategoryCorrectionService
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** How far back the queue looks, and how much it reads and returns. */
data class ReviewSettings(
    val window: Duration = Duration.ofDays(90),
    val scanLimit: Int = 2_000,
    val maxQuestions: Int = 50
) {
    init {
        require(!window.isNegative && !window.isZero) { "The window is positive" }
        require(scanLimit >= 1 && maxQuestions >= 1) { "Limits are positive" }
    }
}

/** What adding to the local golden set did. */
sealed interface GoldenOutcome {
    data class Added(val candidate: GoldenCandidate) : GoldenOutcome
    /** The same scrubbed row is already there. */
    data object AlreadyThere : GoldenOutcome
    data class Refused(val reason: ScrubRefusal) : GoldenOutcome
}

sealed interface ReviewAnswer {
    /** No such transaction for this user. */
    data object NotFound : ReviewAnswer

    /** The answer is now the payee's remembered rule (D-41); [golden] is null when no copy was asked for. */
    data class Answered(val correction: CategoryCorrection, val golden: GoldenOutcome?) : ReviewAnswer
}

/**
 * The "Check these categories" queue (AOS-708): active learning over the learned categoriser's and the AI's guesses.
 * - **Ordered** by uncertainty × amount (least-confidence sampling, weighted so a big unsure spend comes first).
 * - **Answering** (confirm or correct) is a remembered correction (D-41): the payee's rule, past rows re-labelled,
 *   audited like any other category change. The learned model learns from it at its next training (AOS-707).
 * - **A scrubbed copy** may be added to the user's local golden set ([GoldenScrubber]); it never leaves their database
 *   unless they export it, and it is reviewed by hand before anything is committed.
 */
class CategoryReviewService(
    private val transactions: TransactionRepository,
    private val corrections: CategoryCorrectionService,
    private val golden: GoldenCandidateRepository,
    private val audit: AuditService,
    private val settings: ReviewSettings = ReviewSettings(),
    private val clock: Clock = Clock.systemUTC()
) {
    /** The open questions, highest priority first (ties: newest first), at most [limit]. */
    suspend fun questions(userId: UUID, limit: Int = settings.maxQuestions): List<CategoryQuestion> {
        require(limit >= 1) { "Ask for at least one question" }
        val since = clock.instant().minus(settings.window)
        return transactions.findByUserId(userId, from = since, limit = settings.scanLimit)
            .mapNotNull { CategoryQuestion.of(it) }
            .sortedWith(compareByDescending<CategoryQuestion> { it.priority }.thenByDescending { it.transaction.transactedAt }.thenBy { it.transaction.id })
            .take(minOf(limit, settings.maxQuestions))
    }

    suspend fun answer(userId: UUID, transactionId: UUID, category: TransactionCategory, addToGolden: Boolean): ReviewAnswer {
        val txn = transactions.findById(transactionId)?.takeIf { it.userId == userId } ?: return ReviewAnswer.NotFound
        val correction = corrections.correct(txn, category, subcategory = null, remember = true)
        audit.log(
            userId = userId, actor = ActorType.USER, action = AuditActions.TRANSACTION_UPDATE_CATEGORY, entityType = "transaction", entityId = txn.id,
            beforeState = JsonObject(mapOf("category" to JsonPrimitive(txn.category.name.lowercase()), "category_source" to JsonPrimitive(txn.categorySource))),
            afterState = JsonObject(mapOf("category" to JsonPrimitive(category.name.lowercase()), "category_source" to JsonPrimitive(CategoryCorrectionService.SOURCE_USER),
                "remembered" to JsonPrimitive(correction.remembered), "relabelled" to JsonPrimitive(correction.relabelled), "via" to JsonPrimitive("review")))
        )
        val goldenOutcome = if (addToGolden) addToGolden(txn, category) else null
        return ReviewAnswer.Answered(correction, goldenOutcome)
    }

    suspend fun goldenSet(userId: UUID): List<GoldenCandidate> = golden.list(userId)

    suspend fun removeFromGoldenSet(userId: UUID, id: UUID): Boolean = golden.delete(userId, id)

    private suspend fun addToGolden(txn: dev.adminos.api.domain.financial.Transaction, category: TransactionCategory): GoldenOutcome =
        when (val scrubbed = GoldenScrubber.scrub(txn, category, clock.instant())) {
            is ScrubResult.Refused -> GoldenOutcome.Refused(scrubbed.reason)
            is ScrubResult.Scrubbed -> if (golden.add(scrubbed.candidate)) GoldenOutcome.Added(scrubbed.candidate) else GoldenOutcome.AlreadyThere
        }

    companion object {
        /** The local golden set as CSV in `golden.csv`'s format (D-61), with a header saying what it is and what to do. */
        fun csv(rows: List<GoldenCandidate>): String = buildString {
            appendLine("# AdminOS local golden set (AOS-708): scrubbed copies of your own answers. LOCAL ONLY: never commit this file as is.")
            appendLine("# Before moving any row into services/api/src/main/resources/categorization/golden.csv, check it by hand (AOS-511):")
            appendLine("# no person's name, no phone number, no reference. Evaluate it locally with: ./gradlew -q categorizationReport -PlocalGolden=<this file>")
            appendLine("id,merchant_raw,upi_vpa,payment_method,direction,expected_category,expected_subcategory,tags")
            rows.forEach { appendLine(it.csvLine()) }
        }
    }
}
