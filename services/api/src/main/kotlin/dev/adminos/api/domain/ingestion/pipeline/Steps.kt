package dev.adminos.api.domain.ingestion.pipeline

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.ingestion.events.IngestDirection

/** Builds the transaction from the event. Debit/credit come straight from the source (AOS-205). */
class ToTransactionStep : IngestStep {
    private companion object {
        /** Markers that travel to the transaction: the mandate ones (AOS-502) and the pay-later provider (AOS-909). */
        val CARRIED_METADATA = setOf("mandate", "umn", dev.adminos.api.domain.ingestion.sms.PayLaterSmsParser.PAY_LATER_MARKER)
    }

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
            category = e.category ?: dev.adminos.api.domain.financial.TransactionCategory.OTHER,
            categorySource = if (e.category != null) "user" else "rules",
            transactedAt = e.occurredAt,
            // Only the mandate markers travel to the transaction (AOS-502): they let its debit be matched.
            metadata = e.metadata?.filterKeys { it in CARRIED_METADATA }?.takeIf { it.isNotEmpty() }
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

/** Assigns a category with the single categorizer (AOS-210): remembered rule → built-in merchants → patterns → Other. */
class CategorizeStep(private val categorizer: CategorizationService) : IngestStep {
    override suspend fun apply(context: PipelineContext): StepResult {
        val t = context.txn
        if (t.categorySource == "user") return StepResult.Continue(context) // chosen at entry (AOS-204)
        val result = categorizer.categorize(t)
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
        if (detector.isPayLaterRepayment(t)) { // the purchases were the spending (D-39, AOS-909)
            return StepResult.Continue(context.copy(transaction = asTransfer(t, dev.adminos.api.domain.financial.BillPaymentMatcher.PAY_LATER_BILL_TRANSFER)))
        }
        if (detector.isCashWithdrawal(t)) { // money moved to your cash (D-42); logged cash spends are the spending
            return StepResult.Continue(context.copy(transaction = asTransfer(t, "cash")))
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

/**
 * Refunds and reversals (AOS-209). Runs last: only spending/income can become a refund or reversal.
 * Looks both ways, because statement rows can be processed before the payment they reverse.
 */
class RefundReversalStep(
    private val transactions: dev.adminos.api.domain.financial.TransactionRepository,
    private val links: dev.adminos.api.domain.financial.links.TransactionLinkRepository,
    private val detector: dev.adminos.api.domain.financial.links.RefundReversalDetector
) : IngestStep {
    override suspend fun apply(context: PipelineContext): StepResult {
        val t = context.txn
        val det = dev.adminos.api.domain.financial.links.RefundReversalDetector
        val day = java.time.Duration.ofDays(1)
        val pool = when (t.type) {
            dev.adminos.api.domain.financial.TransactionType.CREDIT -> transactions.findInWindow(
                t.userId, dev.adminos.api.domain.financial.TransactionType.DEBIT,
                t.transactedAt.minus(det.REFUND_WINDOW), t.transactedAt.plus(day), minAmount = t.amount)
            dev.adminos.api.domain.financial.TransactionType.DEBIT -> transactions.findInWindow(
                t.userId, dev.adminos.api.domain.financial.TransactionType.CREDIT,
                t.transactedAt.minus(day), t.transactedAt.plus(det.REFUND_WINDOW), maxAmount = t.amount)
            else -> return StepResult.Continue(context)
        }
        if (pool.isEmpty()) return StepResult.Continue(context)
        val debits = pool.filter { it.type == dev.adminos.api.domain.financial.TransactionType.DEBIT }
        val refunded = debits.associate { it.id to refundedSoFar(it.id) }
        return when (val d = detector.decide(t, pool, refunded)) {
            dev.adminos.api.domain.financial.links.RefundDecision.None -> StepResult.Continue(context)
            is dev.adminos.api.domain.financial.links.RefundDecision.Link -> {
                val incomingIsCredit = d.credit.id == t.id
                val existing = if (incomingIsCredit) d.original else d.credit
                val (newOriginal, newCredit) = if (d.auto) applyRelation(d.relation, d.original, d.credit) else d.original to d.credit
                val self = if (incomingIsCredit) newCredit else newOriginal
                val updatedExisting = if (d.auto) (if (incomingIsCredit) newOriginal else newCredit) else null
                StepResult.Continue(context.copy(
                    transaction = self,
                    relations = context.relations + Relation(d.relation, existing, updatedExisting, d.auto, d.score, d.reason)
                ))
            }
        }
    }

    private suspend fun refundedSoFar(debitId: java.util.UUID): java.math.BigDecimal =
        links.findByTransaction(debitId)
            .filter { it.relation == dev.adminos.api.domain.financial.links.LinkRelation.REFUND && it.kind != dev.adminos.api.domain.financial.links.LinkKind.REJECTED }
            .mapNotNull { l -> listOfNotNull(l.transactionId, l.relatedTransactionId).firstOrNull { it != debitId }?.let { transactions.findById(it) } }
            .fold(java.math.BigDecimal.ZERO) { acc, c -> acc + c.amount }
}

/**
 * Applies a refund/reversal to the pair (debit, credit):
 * - reversal: both become `reversal`; the credit takes the debit's category
 * - refund: the credit becomes `refund` with the debit's category, so it nets that category's spending
 * A category the user chose is kept.
 */
internal fun applyRelation(
    relation: dev.adminos.api.domain.financial.links.LinkRelation,
    debit: dev.adminos.api.domain.financial.Transaction,
    credit: dev.adminos.api.domain.financial.Transaction
): Pair<dev.adminos.api.domain.financial.Transaction, dev.adminos.api.domain.financial.Transaction> {
    fun inherit(c: dev.adminos.api.domain.financial.Transaction) =
        if (c.categorySource == "user") c else c.copy(category = debit.category, subcategory = debit.subcategory, categorySource = "linked")
    return when (relation) {
        dev.adminos.api.domain.financial.links.LinkRelation.REVERSAL ->
            debit.copy(nature = dev.adminos.api.domain.financial.TransactionNature.REVERSAL) to
                inherit(credit).copy(nature = dev.adminos.api.domain.financial.TransactionNature.REVERSAL)
        dev.adminos.api.domain.financial.links.LinkRelation.REFUND ->
            debit to inherit(credit).copy(nature = dev.adminos.api.domain.financial.TransactionNature.REFUND)
        else -> debit to credit
    }
}
