package dev.adminos.api.domain.financial.splits

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

sealed interface SplitMatch {
    data object None : SplitMatch
    data class Apply(val transaction: Transaction, val score: Int, val reason: String) : SplitMatch
    data class Suggest(val transaction: Transaction, val score: Int, val reason: String) : SplitMatch
}

/** What the matcher needs to know about an entry (stored or just read). */
data class MatchQuery(
    val userId: UUID,
    val effect: SplitEffect,
    val date: LocalDate,
    val bankAmount: BigDecimal,
    val currency: String,
    val description: String,
    val category: String?,
    val counterparty: String?,
    /** Never proposed: the owner rejected them, or another entry already holds them. */
    val excluded: Set<UUID> = emptySet()
)

/**
 * Finds the bank transaction a Splitwise entry is about (AOS-911, D-111). Same shape as the cross-source matcher
 * (D-38): hard rules first, then points with an apply band, a suggest band and an ambiguity margin.
 *
 * Hard rules: same user and currency, exact amount, the right direction and nature (a spend for a bill or a
 * settle-up paid, income for a settle-up received), not already split, not a Splitwise-made row, inside the window.
 * - **Bill the owner paid** (the whole cost): the bank day may be 1 before to 3 after the Splitwise date (cards post late).
 * - **Settle-up:** ±2 days. It turns a spend or income into neither, so it is applied only with the friend's name in
 *   the bank narration or UPI ID; otherwise it is only suggested.
 */
class SplitMatcher(private val zone: ZoneId = ZoneId.of("Asia/Kolkata")) {

    companion object {
        const val RULES_VERSION = "split-v1"
        const val APPLY_AT = 7
        const val APPLY_SETTLEMENT_AT = 9
        const val SUGGEST_AT = 5
        const val AMBIGUITY_MARGIN = 3
        val BILL_DAYS = -1L..3L
        val SETTLE_DAYS = -2L..2L
        const val SPLITWISE_SOURCE = "splitwise"
        private val STOP = setOf("the", "and", "for", "with", "from", "paid", "payment", "upi", "imps", "neft", "dinner", "lunch", "share")
    }

    /** The bank-day window to search, for the repository query. */
    fun window(effect: SplitEffect): LongRange = if (effect == SplitEffect.ADJUST_DEBIT) BILL_DAYS else SETTLE_DAYS

    fun decide(q: MatchQuery, candidates: List<Transaction>): SplitMatch {
        require(q.effect.needsMatch) { "only bills the owner paid and settle-ups are matched" }
        val scored = candidates.filter { eligible(q, it) }
            .map { t -> score(q, t).let { (s, why, named) -> Scored(t, s, why, named) } }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { kotlin.math.abs(dayOffset(q, it.t)) }.thenBy { it.t.id })
        val best = scored.firstOrNull() ?: return SplitMatch.None
        if (best.score < SUGGEST_AT) return SplitMatch.None
        val ambiguous = scored.getOrNull(1)?.let { best.score - it.score < AMBIGUITY_MARGIN } ?: false
        val settlement = q.effect != SplitEffect.ADJUST_DEBIT
        val applies = !ambiguous && if (settlement) best.named && best.score >= APPLY_SETTLEMENT_AT else best.score >= APPLY_AT
        return when {
            applies -> SplitMatch.Apply(best.t, best.score, best.why)
            ambiguous -> SplitMatch.Suggest(best.t, best.score, "${best.why}; another similar transaction exists")
            settlement && !best.named -> SplitMatch.Suggest(best.t, best.score, "${best.why}; the friend's name isn't in the bank record")
            else -> SplitMatch.Suggest(best.t, best.score, best.why)
        }
    }

    /** True when [t] could be this entry's transaction at all (also used when the owner picks one by hand). */
    fun eligible(q: MatchQuery, t: Transaction): Boolean {
        val (type, nature) = when (q.effect) {
            SplitEffect.ADJUST_DEBIT, SplitEffect.SETTLE_OUT -> TransactionType.DEBIT to TransactionNature.SPEND
            SplitEffect.SETTLE_IN -> TransactionType.CREDIT to TransactionNature.INCOME
            else -> return false
        }
        return t.userId == q.userId && t.id !in q.excluded && t.type == type && t.nature == nature &&
            t.ownShare == null && t.sourceType != SPLITWISE_SOURCE && t.currency == q.currency &&
            t.amount.compareTo(q.bankAmount) == 0 && dayOffset(q, t) in window(q.effect)
    }

    private data class Scored(val t: Transaction, val score: Int, val why: String, val named: Boolean)

    private fun score(q: MatchQuery, t: Transaction): Triple<Int, String, Boolean> {
        val why = mutableListOf("same amount")
        var score = 4
        val days = dayOffset(q, t)
        score += when (kotlin.math.abs(days)) { 0L -> 3; 1L -> 2; else -> 1 }
        why += if (days == 0L) "same day" else "${kotlin.math.abs(days)} day(s) ${if (days > 0) "later" else "earlier"}"
        var named = false
        if (q.effect == SplitEffect.ADJUST_DEBIT) {
            if (shares(tokens(q.description), bankTokens(t))) { score += 3; why += "the description names the merchant" }
            val mapped = SplitwiseCategories.categoryOf(q.category)
            if (mapped != TransactionCategory.OTHER && mapped == t.category) { score += 2; why += "same category" }
        } else {
            named = q.counterparty != null && names(q.counterparty, t)
            if (named) { score += 4; why += "the friend's name is on the payment" }
            if (t.paymentMethod == PaymentMethod.UPI) { score += 1; why += "UPI" }
        }
        return Triple(score, why.joinToString(", "), named)
    }

    /** Bank day minus Splitwise day, in the user's zone. */
    private fun dayOffset(q: MatchQuery, t: Transaction): Long =
        ChronoUnit.DAYS.between(q.date, t.transactedAt.atZone(zone).toLocalDate())

    /** A name token (3+ letters) as a whole word of the narration, or inside the UPI ID's handle. */
    private fun names(person: String, t: Transaction): Boolean {
        val person = tokens(person)
        if (person.isEmpty()) return false
        val handle = t.upiVpa?.substringBefore('@')?.lowercase().orEmpty()
        return shares(person, bankTokens(t)) || person.any { it.length >= 4 && handle.contains(it) }
    }

    private fun shares(a: Set<String>, b: Set<String>) = a.isNotEmpty() && a.any { it in b }

    private fun bankTokens(t: Transaction) = tokens(listOfNotNull(t.merchantName, t.merchantRaw).joinToString(" "))

    private fun tokens(s: String?): Set<String> =
        SplitwiseCsv.identity(s.orEmpty()).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in STOP }.toSet()
}
