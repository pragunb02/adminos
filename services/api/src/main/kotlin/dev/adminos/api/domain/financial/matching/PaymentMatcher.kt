package dev.adminos.api.domain.financial.matching

import dev.adminos.api.domain.financial.MerchantCatalogue
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionStatus
import dev.adminos.api.domain.financial.TransactionType
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs

/**
 * When a payment for a claim may have happened, relative to the claim's time.
 * - [before]/[after]: for sources with a real time (SMS, email, WhatsApp);
 * - [days]/[cardDays]: for date-only sources (statements, manual entries), in local days after the
 *   claim's date (cards post later).
 */
data class PaymentWindow(val before: Duration, val after: Duration, val days: IntRange, val cardDays: IntRange) {
    init {
        require(!before.isNegative && !after.isNegative) { "window durations can't be negative" }
        require(!days.isEmpty() && !cardDays.isEmpty()) { "day windows can't be empty" }
    }
}

/**
 * Something that says "a payment of this amount, to this payee, around this time" (AOS-903):
 * an order receipt today, a travel booking or another document later.
 */
data class PaymentClaim(
    val userId: UUID,
    /** The exact amounts the payment would have, preferred first. */
    val amounts: List<BigDecimal>,
    val at: Instant,
    val window: PaymentWindow,
    /** The payee's brand; its first word in the payment's merchant, narration or UPI ID is evidence. */
    val payee: String,
    /** How the claim says it was paid, when it says. */
    val method: PaymentMethod? = null
) {
    init {
        require(amounts.isNotEmpty() && amounts.all { it.signum() > 0 }) { "a claim needs positive amounts" }
        require(payee.isNotBlank()) { "a claim needs a payee" }
    }
}

sealed interface PaymentMatch {
    data object None : PaymentMatch
    /** Strong enough to link without asking. */
    data class Auto(val payment: Transaction, val score: Int, val reason: String) : PaymentMatch
    /** Worth showing the user, who confirms or rejects. */
    data class Suggest(val payment: Transaction, val score: Int, val reason: String) : PaymentMatch
}

/**
 * Finds the transaction that paid a [PaymentClaim] (AOS-903). The same ideas as cross-source dedup
 * (D-38), as a reusable service:
 * - hard rules first: same user, a debit that is spending, an exact amount, inside the window, not
 *   already taken by another claim, not a pair the user rejected, not cash;
 * - then points: exact amount 4; time 3 (within 30 minutes, or the same day for date-only rows) or
 *   less further out; payee evidence 4; the same payment method 1;
 * - bands: ≥ 8 with payee evidence and a margin of 3 over the runner-up is linked; anything else
 *   that passed the hard rules is suggested (the weakest, a date-only row the next day with no
 *   payee evidence, scores [SUGGEST_AT]). Without payee evidence nothing is linked automatically: an
 *   amount and a time alone could be anyone's ₹199.
 * Pure: no I/O, deterministic for the same input.
 */
class PaymentMatcher(private val zone: ZoneId = ZoneId.of("Asia/Kolkata")) {

    companion object {
        const val RULES_VERSION = "payment-match-v1"
        const val AUTO_AT = 8
        /** The lowest score an eligible payment can have: every candidate that passes the hard rules is at least a suggestion. */
        const val SUGGEST_AT = 5
        const val AMBIGUITY_MARGIN = 3
        private val CLOSE = Duration.ofMinutes(30)
        private val DATE_ONLY_SOURCES = setOf("statement", "manual")
    }

    private data class Scored(val payment: Transaction, val score: Int, val payeeEvidence: Boolean, val gap: Long, val reason: String)

    fun decide(claim: PaymentClaim, candidates: List<Transaction>, excluded: Set<UUID> = emptySet()): PaymentMatch {
        val ranked = candidates.asSequence()
            .distinctBy { it.id }
            .filter { eligible(claim, it, excluded) }
            .mapNotNull { score(claim, it) }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.gap }.thenBy { it.payment.id })
            .toList()
        val best = ranked.firstOrNull() ?: return PaymentMatch.None
        val runnerUp = ranked.getOrNull(1)
        val ambiguous = runnerUp != null && best.score - runnerUp.score < AMBIGUITY_MARGIN
        return when {
            best.score >= AUTO_AT && best.payeeEvidence && !ambiguous -> PaymentMatch.Auto(best.payment, best.score, best.reason)
            ambiguous -> PaymentMatch.Suggest(best.payment, best.score, "${best.reason}; another similar payment exists")
            !best.payeeEvidence -> PaymentMatch.Suggest(best.payment, best.score, "${best.reason}; the payee doesn't name ${claim.payee}")
            else -> PaymentMatch.Suggest(best.payment, best.score, best.reason)
        }
    }

    /** Hard rules: anything failing one is a different payment. */
    private fun eligible(claim: PaymentClaim, t: Transaction, excluded: Set<UUID>): Boolean =
        t.userId == claim.userId && t.id !in excluded &&
            t.type == TransactionType.DEBIT && t.nature == TransactionNature.SPEND &&
            t.status != TransactionStatus.FAILED && t.status != TransactionStatus.REVERSED &&
            t.paymentMethod != PaymentMethod.CASH &&
            claim.amounts.any { it.compareTo(t.amount) == 0 }

    private fun score(claim: PaymentClaim, t: Transaction): Scored? {
        val timing = timing(claim, t) ?: return null
        var score = 4
        val why = mutableListOf("same amount", timing.label)
        score += timing.points
        val evidence = namesPayee(t, claim.payee)
        if (evidence) { score += 4; why += "paid to ${claim.payee}" }
        if (claim.method != null && sameMethod(claim.method, t.paymentMethod)) { score += 1; why += "same payment method" }
        return Scored(t, score, evidence, timing.gap, why.joinToString(", "))
    }

    private data class Timing(val points: Int, val gap: Long, val label: String)

    private fun timing(claim: PaymentClaim, t: Transaction): Timing? {
        val gapSeconds = Duration.between(claim.at, t.transactedAt).seconds
        if (t.sourceType in DATE_ONLY_SOURCES) {
            val days = ChronoUnit.DAYS.between(claim.at.atZone(zone).toLocalDate(), t.transactedAt.atZone(zone).toLocalDate()).toInt()
            val card = t.paymentMethod == PaymentMethod.CARD_CREDIT || t.paymentMethod == PaymentMethod.CARD_DEBIT
            if (days !in (if (card) claim.window.cardDays else claim.window.days)) return null
            return if (days == 0) Timing(3, abs(gapSeconds), "same day") else Timing(1, abs(gapSeconds), "within $days day(s)")
        }
        val offset = Duration.ofSeconds(gapSeconds)
        if (offset < claim.window.before.negated() || offset > claim.window.after) return null
        return if (offset.abs() <= CLOSE) Timing(3, abs(gapSeconds), "within 30 minutes") else Timing(2, abs(gapSeconds), "within the order window")
    }

    /** Whether the payment names the payee's brand (its first word, as a whole word): "Swiggy Instamart" ← "SWIGGY". */
    internal fun namesPayee(t: Transaction, payee: String): Boolean {
        val word = MerchantCatalogue.matchForm(payee).substringBefore(' ')
        if (word.length < 3) return false
        val pattern = Regex("(?<![A-Z0-9])${Regex.escape(word)}(?![A-Z0-9])")
        return listOfNotNull(t.merchantName, t.merchantRaw, t.upiVpa).any { pattern.containsMatchIn(MerchantCatalogue.matchForm(it)) }
    }

    private fun sameMethod(claimed: PaymentMethod, actual: PaymentMethod?): Boolean = when (claimed) {
        PaymentMethod.CARD_CREDIT, PaymentMethod.CARD_DEBIT -> actual == PaymentMethod.CARD_CREDIT || actual == PaymentMethod.CARD_DEBIT
        else -> actual == claimed
    }
}
