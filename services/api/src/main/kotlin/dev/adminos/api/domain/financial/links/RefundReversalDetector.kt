package dev.adminos.api.domain.financial.links

import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import java.math.BigDecimal
import java.time.Duration
import java.time.ZoneId
import java.time.temporal.ChronoUnit

sealed interface RefundDecision {
    data object None : RefundDecision
    /** [original] is the debit, [credit] the money back; one of them is the incoming transaction. */
    data class Link(val relation: LinkRelation, val original: Transaction, val credit: Transaction, val score: Int, val reason: String, val auto: Boolean) : RefundDecision
}

/**
 * Refunds and reversals (AOS-209, D-39).
 * - **Reversal:** a failed payment and its automatic credit back, with the exact amount within 5 days
 *   (RBI turnaround T+1…T+5). Both become `reversal`, netting to zero.
 * - **Refund:** money back from a merchant, up to the original amount, within 90 days. The credit becomes
 *   `refund` with the original's category, so it reduces that category's spending instead of counting as income.
 * Works in both directions, because statement rows can be processed in any order.
 */
class RefundReversalDetector(private val zone: ZoneId = ZoneId.of("Asia/Kolkata")) {

    companion object {
        val REVERSAL_WINDOW: Duration = Duration.ofDays(5)
        val REFUND_WINDOW: Duration = Duration.ofDays(90)
        private val REVERSAL_HINT = Regex("\\b(REVERSAL|REVERSED|REV|FAILED|FAILURE|RETURN(ED)?|RTN|CHARGEBACK|DECLINED)\\b")
        private val REFUND_HINT = Regex("\\b(REFUND(ED)?|RFND|RFD|REF CR|CREDIT FOR)\\b")
    }

    /**
     * @param candidates opposite-direction transactions near [incoming]
     * @param refundedSoFar already-linked refund total per original debit id
     */
    fun decide(incoming: Transaction, candidates: List<Transaction>, refundedSoFar: Map<java.util.UUID, BigDecimal> = emptyMap()): RefundDecision {
        if (incoming.nature !in setOf(TransactionNature.SPEND, TransactionNature.INCOME)) return RefundDecision.None
        // A Splitwise share was never paid to a merchant, so nothing refunds it (AOS-911); friends settle it instead.
        val options = candidates.filter {
            it.userId == incoming.userId && it.id != incoming.id && it.nature in setOf(TransactionNature.SPEND, TransactionNature.INCOME) &&
                it.sourceType != dev.adminos.api.domain.financial.splits.SplitMatcher.SPLITWISE_SOURCE
        }
            .mapNotNull { other ->
                val (debit, credit) = when {
                    incoming.type == TransactionType.CREDIT && other.type == TransactionType.DEBIT -> other to incoming
                    incoming.type == TransactionType.DEBIT && other.type == TransactionType.CREDIT -> incoming to other
                    else -> return@mapNotNull null
                }
                reversal(debit, credit) ?: refund(debit, credit, refundedSoFar[debit.id] ?: BigDecimal.ZERO)
            }
            .sortedByDescending { it.score }
        val best = options.firstOrNull() ?: return RefundDecision.None
        val runnerUp = options.getOrNull(1)?.score
        val ambiguous = runnerUp != null && best.score - runnerUp < 3
        return if (ambiguous) best.copy(auto = false, reason = "${best.reason}; another similar transaction exists") else best
    }

    private fun reversal(debit: Transaction, credit: Transaction): RefundDecision.Link? {
        if (debit.amount.compareTo(credit.amount) != 0 || !sameAccountOrUnknown(debit, credit)) return null
        val days = daysAfter(debit, credit) ?: return null
        if (days !in 0..REVERSAL_WINDOW.toDays()) return null
        var score = 4
        val why = mutableListOf("same amount and account")
        if (debit.paymentRef != null && debit.paymentRef == credit.paymentRef) { score += 6; why += "same payment reference" }
        if (REVERSAL_HINT.containsMatchIn(text(credit))) { score += 4; why += "reversal narration" }
        score += when (days) { 0L -> 2; 1L -> 1; else -> 0 }
        why += if (days == 0L) "same day" else "$days day(s) later"
        if (score < 6) return null
        if (REFUND_HINT.containsMatchIn(text(credit)) && !REVERSAL_HINT.containsMatchIn(text(credit))) return null // a refund, not a reversal
        return RefundDecision.Link(LinkRelation.REVERSAL, debit, credit, score, why.joinToString(", "), auto = score >= 8)
    }

    private fun refund(debit: Transaction, credit: Transaction, refundedSoFar: BigDecimal): RefundDecision.Link? {
        if (credit.amount > debit.amount || refundedSoFar + credit.amount > debit.amount || !sameAccountOrUnknown(debit, credit)) return null
        val days = daysAfter(debit, credit) ?: return null
        if (days !in 0..REFUND_WINDOW.toDays()) return null
        val merchant = merchantMatch(debit, credit)
        if (merchant == 0) return null // a refund must come from the same merchant
        var score = merchant
        val why = mutableListOf(if (merchant >= 3) "same merchant" else "similar merchant")
        if (REFUND_HINT.containsMatchIn(text(credit))) { score += 4; why += "refund narration" }
        if (credit.amount.compareTo(debit.amount) == 0) { score += 2; why += "full amount" } else why += "partial"
        if (debit.accountLast4 != null && debit.accountLast4 == credit.accountLast4) { score += 1; why += "same account" }
        if (score < 5) return null
        return RefundDecision.Link(LinkRelation.REFUND, debit, credit, score, why.joinToString(", "), auto = score >= 8)
    }

    /** Whole days from debit to credit in IST; null if the credit is clearly before the debit. */
    private fun daysAfter(debit: Transaction, credit: Transaction): Long? {
        val d = ChronoUnit.DAYS.between(debit.transactedAt.atZone(zone).toLocalDate(), credit.transactedAt.atZone(zone).toLocalDate())
        return if (d < 0) null else d
    }

    private fun sameAccountOrUnknown(a: Transaction, b: Transaction) =
        a.accountLast4 == null || b.accountLast4 == null || a.accountLast4 == b.accountLast4

    /** 3 = same merchant (name or UPI ID), 1–2 = overlapping name tokens, 0 = unrelated. */
    private fun merchantMatch(a: Transaction, b: Transaction): Int {
        if (a.upiVpa != null && a.upiVpa.equals(b.upiVpa, ignoreCase = true)) return 3
        val ta = tokens(a.merchantName); val tb = tokens(b.merchantName)
        if (ta.isNotEmpty() && ta == tb) return 3
        val shared = (ta intersect tb).size
        return when { shared == 0 -> 0; shared * 2 >= minOf(ta.size, tb.size) -> 2; else -> 1 }
    }

    private fun tokens(s: String?) = s.orEmpty().lowercase().split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 3 && it !in setOf("upi", "pos", "refund", "rfnd", "ecom", "pvt", "ltd", "india", "payment") }.toSet()

    private fun text(t: Transaction) = listOfNotNull(t.merchantRaw, t.merchantName).joinToString(" ").uppercase()
}
