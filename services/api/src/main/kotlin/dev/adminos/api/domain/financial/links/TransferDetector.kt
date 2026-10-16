package dev.adminos.api.domain.financial.links

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import java.time.Duration
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

sealed interface TransferDecision {
    data object None : TransferDecision
    data class Pair(val counterpart: Transaction, val score: Int, val reason: String, val auto: Boolean) : TransferDecision
}

/**
 * Own-account transfers (AOS-208, D-39). Money moved between your own accounts is neither spending nor income.
 * - **Card bill payments** (bank → your credit card) are recognised from the narration. Without this every
 *   card purchase would count twice: once when spent, again when the bill is paid.
 * - **Pairs:** a debit on one of your accounts and a credit of the exact amount on another, close in time.
 */
class TransferDetector(private val zone: ZoneId = ZoneId.of("Asia/Kolkata")) {

    companion object {
        const val AUTO_AT = 8
        const val SUGGEST_AT = 6
        const val AMBIGUITY_MARGIN = 3
        val WINDOW: Duration = Duration.ofDays(2)

        private val CARD_BILL = listOf(
            Regex("\\bCREDIT\\s*CARD\\b"), Regex("\\bCC\\s*(PAYMENT|PMT|BILL)\\b"), Regex("\\bCCPAYMENT\\b"),
            Regex("\\bCARD\\s*BILL\\b"), Regex("\\bBILLDESK\\b.*\\bCARD\\b"), Regex("\\bCARD\\b.*\\bBILLDESK\\b"),
            Regex("\\bCRED\\b"), Regex("CRED\\.CLUB"), Regex("\\bAUTOPAY\\b.*\\bCARD\\b")
        )
        private val TRANSFER_HINT = Regex("\\b(TRANSFER|TRF|SELF|OWN A/?C|IMPS|NEFT|RTGS|FT|TO A/?C|FROM A/?C|SWEEP)\\b")
        private val REALTIME_SOURCES = setOf("sms", "gmail", "whatsapp", "manual")
    }

    /** A debit paying one of your credit cards (not a purchase made with a card). */
    fun isCardBillPayment(t: Transaction): Boolean {
        if (t.type != TransactionType.DEBIT) return false
        if (t.paymentMethod == PaymentMethod.CARD_CREDIT || t.paymentMethod == PaymentMethod.CARD_DEBIT) return false
        val text = text(t)
        return CARD_BILL.any { it.containsMatchIn(text) }
    }

    fun decide(incoming: Transaction, candidates: List<Transaction>): TransferDecision {
        val scored = candidates.filter { eligible(incoming, it) }
            .mapNotNull { c -> score(incoming, c)?.let { (s, why) -> Triple(c, s, why) } }
            .sortedWith(compareByDescending<Triple<Transaction, Int, String>> { it.second }
                .thenBy { abs(Duration.between(it.first.transactedAt, incoming.transactedAt).seconds) })
        val best = scored.firstOrNull() ?: return TransferDecision.None
        if (best.second < SUGGEST_AT) return TransferDecision.None
        val ambiguous = scored.getOrNull(1)?.let { best.second - it.second < AMBIGUITY_MARGIN } ?: false
        val auto = best.second >= AUTO_AT && !ambiguous
        return TransferDecision.Pair(best.first, best.second, if (ambiguous) "${best.third}; another similar transaction exists" else best.third, auto)
    }

    private fun eligible(a: Transaction, b: Transaction): Boolean =
        a.userId == b.userId && a.id != b.id && setOf(a.type, b.type) == setOf(TransactionType.DEBIT, TransactionType.CREDIT) &&
            a.amount.compareTo(b.amount) == 0 && a.currency == b.currency &&
            a.accountLast4 != null && b.accountLast4 != null && a.accountLast4 != b.accountLast4 &&
            b.nature in setOf(TransactionNature.SPEND, TransactionNature.INCOME)

    private fun score(a: Transaction, b: Transaction): Pair<Int, String>? {
        val gap = Duration.between(a.transactedAt, b.transactedAt).abs()
        if (gap > WINDOW) return null
        var score = 4
        val why = mutableListOf("same amount, two of your accounts")
        val statement = a.sourceType == "statement" || b.sourceType == "statement"
        if (!statement && a.sourceType in REALTIME_SOURCES && b.sourceType in REALTIME_SOURCES) {
            when {
                gap <= Duration.ofMinutes(10) -> { score += 3; why += "within 10 minutes" }
                gap <= Duration.ofHours(1) -> { score += 1; why += "within an hour" }
                else -> return null
            }
        } else {
            val days = abs(ChronoUnit.DAYS.between(a.transactedAt.atZone(zone).toLocalDate(), b.transactedAt.atZone(zone).toLocalDate()))
            when (days) {
                0L -> { score += 2; why += "same day" }
                1L -> { score += 1; why += "next day" }
                else -> return null
            }
        }
        if (TRANSFER_HINT.containsMatchIn(text(a)) || TRANSFER_HINT.containsMatchIn(text(b))) { score += 3; why += "transfer narration" }
        if (mentions(a, b.accountLast4) || mentions(b, a.accountLast4)) { score += 5; why += "names the other account" }
        return score to why.joinToString(", ")
    }

    private fun mentions(t: Transaction, last4: String?): Boolean =
        last4 != null && Regex("(?<!\\d)(?:[X*x]+)?$last4(?!\\d)").containsMatchIn(t.merchantRaw.orEmpty())

    private fun text(t: Transaction) = listOfNotNull(t.merchantRaw, t.merchantName, t.upiVpa).joinToString(" ").uppercase()
}
