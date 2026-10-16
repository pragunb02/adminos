package dev.adminos.api.domain.ingestion.pipeline

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.ingestion.events.IngestDirection

/** Builds the transaction from the event. Debit/credit come straight from the source (AOS-205). */
class ToTransactionStep : IngestStep {
    override suspend fun apply(context: PipelineContext): StepResult {
        val e = context.event
        val txn = Transaction(
            userId = e.userId,
            sourceType = e.source.name.lowercase(),
            sourceRef = e.sourceRef,
            type = when (e.direction) {
                IngestDirection.DEBIT -> TransactionType.DEBIT
                IngestDirection.CREDIT -> TransactionType.CREDIT
            },
            amount = e.amount,
            currency = e.currency,
            merchantRaw = e.merchantRaw,
            accountLast4 = e.accountHint,
            paymentMethod = e.paymentMethod,
            upiVpa = e.upiVpa,
            paymentRef = e.paymentRef,
            ingestEventId = e.id,
            transactedAt = e.occurredAt
        )
        return StepResult.Continue(context.copy(transaction = txn))
    }
}

/** Sets a clean, consistent merchant name (deterministic; see [MerchantNormalizer]). */
class NormalizeMerchantStep(private val normalizer: MerchantNormalizer) : IngestStep {
    override suspend fun apply(context: PipelineContext): StepResult {
        val t = context.txn
        val name = normalizer.normalize(raw = t.merchantRaw, upiVpa = t.upiVpa)
        return StepResult.Continue(context.copy(transaction = t.copy(merchantName = name)))
    }
}

/** Assigns a category with the rules engine (single categorizer, AOS-210 extends it). */
class CategorizeStep(private val categorizer: CategorizationService) : IngestStep {
    override suspend fun apply(context: PipelineContext): StepResult {
        val t = context.txn
        val result = categorizer.categorize(t.merchantName, t.paymentMethod)
        return StepResult.Continue(
            context.copy(transaction = t.copy(category = result.category, subcategory = result.subcategory, categorySource = result.source))
        )
    }
}

/** Supplies possible same-payment transactions for an incoming one (persistence-agnostic). */
fun interface MatchCandidateSource {
    suspend fun candidatesFor(incoming: Transaction): List<dev.adminos.api.domain.financial.links.MatchCandidate>
}

/**
 * Cross-source duplicate detection (AOS-207). Auto-merges strong matches into the existing transaction;
 * weaker or ambiguous ones become a suggestion the user confirms or rejects.
 */
class CrossSourceDedupStep(
    private val candidates: MatchCandidateSource,
    private val matcher: dev.adminos.api.domain.financial.links.TransactionMatcher
) : IngestStep {
    override suspend fun apply(context: PipelineContext): StepResult {
        val t = context.txn
        return when (val d = matcher.decide(t, candidates.candidatesFor(t))) {
            dev.adminos.api.domain.financial.links.MatchDecision.None -> StepResult.Continue(context)
            is dev.adminos.api.domain.financial.links.MatchDecision.AutoMerge -> StepResult.Merge(d.target, d.score, d.reason)
            is dev.adminos.api.domain.financial.links.MatchDecision.Suggest ->
                StepResult.Continue(context.copy(suggestion = MatchSuggestion(d.target, d.score, d.reason)))
        }
    }
}

/**
 * Own-account transfers (AOS-208): card bill payments, and debit/credit pairs across your accounts.
 * Runs after categorisation so it can set the Transfer category; a category the user chose is kept.
 */
class TransferStep(
    private val candidates: MatchCandidateSource,
    private val detector: dev.adminos.api.domain.financial.links.TransferDetector
) : IngestStep {
    override suspend fun apply(context: PipelineContext): StepResult {
        val t = context.txn
        if (detector.isCardBillPayment(t)) {
            return StepResult.Continue(context.copy(transaction = asTransfer(t, "credit_card_bill")))
        }
        val opposite = if (t.type == dev.adminos.api.domain.financial.TransactionType.DEBIT)
            dev.adminos.api.domain.financial.TransactionType.CREDIT else dev.adminos.api.domain.financial.TransactionType.DEBIT
        val pool = candidates.candidatesFor(t.copy(type = opposite)).map { it.transaction }
        return when (val d = detector.decide(t, pool)) {
            dev.adminos.api.domain.financial.links.TransferDecision.None -> StepResult.Continue(context)
            is dev.adminos.api.domain.financial.links.TransferDecision.Pair -> {
                val relation = Relation(
                    relation = dev.adminos.api.domain.financial.links.LinkRelation.TRANSFER,
                    existing = d.counterpart,
                    updatedExisting = if (d.auto) asTransfer(d.counterpart, "self_transfer") else null,
                    applied = d.auto, score = d.score, reason = d.reason
                )
                val self = if (d.auto) asTransfer(t, "self_transfer") else t
                StepResult.Continue(context.copy(transaction = self, relations = context.relations + relation))
            }
        }
    }
}

/** Marks a transaction as a transfer; keeps a category the user chose themselves. */
internal fun asTransfer(t: dev.adminos.api.domain.financial.Transaction, kind: String) = t.copy(
    nature = dev.adminos.api.domain.financial.TransactionNature.TRANSFER,
    category = if (t.categorySource == "user") t.category else dev.adminos.api.domain.financial.TransactionCategory.TRANSFER,
    subcategory = if (t.categorySource == "user") t.subcategory else kind
)
