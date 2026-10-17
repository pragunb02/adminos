package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.links.TransferDetector
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestSource
import dev.adminos.api.domain.ingestion.pipeline.CategorizeStep
import dev.adminos.api.domain.ingestion.pipeline.IngestPipeline
import dev.adminos.api.domain.ingestion.pipeline.MatchCandidateSource
import dev.adminos.api.domain.ingestion.pipeline.MerchantNormalizer
import dev.adminos.api.domain.ingestion.pipeline.NormalizeMerchantStep
import dev.adminos.api.domain.ingestion.pipeline.PipelineOutcome
import dev.adminos.api.domain.ingestion.pipeline.ToTransactionStep
import dev.adminos.api.domain.ingestion.pipeline.TransferStep
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Labels golden cases with the shipped pipeline steps that decide a category on their own (AOS-511):
 * build → normalize merchant → categorize → own-account transfer rules (card bill, ATM). Dedup, pairing
 * and refund linking need other transactions, so they are not part of a single-row evaluation.
 * The amount is a fixed synthetic ₹100: the categorizer never looks at amounts.
 */
class PipelineLabeller(
    categorizer: CategorizationService = CategorizationService(),
    normalizer: MerchantNormalizer = MerchantNormalizer(),
    private val userId: UUID = UUID(0, 511)
) {
    private val noCandidates = MatchCandidateSource { emptyList() }
    private val pipeline = IngestPipeline(listOf(
        ToTransactionStep(),
        NormalizeMerchantStep(normalizer),
        CategorizeStep(categorizer),
        TransferStep(noCandidates, TransferDetector())
    ))

    /** The transaction the pipeline would store for [case]. */
    suspend fun transactionFor(case: LabelledCase): Transaction {
        val outcome = pipeline.run(eventFor(case))
        check(outcome is PipelineOutcome.Created) { "golden case ${case.id} was not created: $outcome" }
        return outcome.transaction
    }

    suspend fun label(case: LabelledCase): Outcome {
        val t = transactionFor(case)
        return Outcome(case, Prediction(Label(t.category, t.subcategory), t.categorySource))
    }

    suspend fun labelAll(cases: List<LabelledCase>): List<Outcome> = cases.map { label(it) }

    private fun eventFor(case: LabelledCase) = IngestEvent(
        userId = userId,
        source = IngestSource.SMS,
        sourceRef = "golden-${case.id}",
        occurredAt = Instant.parse("2026-10-01T10:00:00Z"),
        direction = if (case.input.type == TransactionType.CREDIT) IngestDirection.CREDIT else IngestDirection.DEBIT,
        amount = BigDecimal("100.00"),
        merchantRaw = case.input.merchantRaw,
        paymentMethod = case.input.paymentMethod,
        upiVpa = case.input.upiVpa
    )
}
