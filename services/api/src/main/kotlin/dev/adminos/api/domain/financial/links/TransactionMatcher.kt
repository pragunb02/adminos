package dev.adminos.api.domain.financial.links

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import java.time.Duration
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/** An existing transaction that might be the same payment, with the sources already merged into it. */
data class MatchCandidate(val transaction: Transaction, val mergedSources: Set<String> = emptySet())

sealed interface MatchDecision {
    data object None : MatchDecision
    data class AutoMerge(val target: Transaction, val score: Int, val reason: String) : MatchDecision
    data class Suggest(val target: Transaction, val score: Int, val reason: String) : MatchDecision
}

/**
 * Decides whether an incoming transaction is the same real payment as an existing one from another
 * source (AOS-207). The research behind it is in the ticket:
 * - pass 1: an identical UPI/IMPS reference is decisive (Actual Budget's imported-id pass);
 * - pass 2: a points score with three bands, as in GnuCash: auto-merge, suggest, keep separate.
 *
 * Hard rules, never merged: a different source is required, plus the same direction and exact amount,
 * no conflicting account or reference, inside the payment-type window, one link per source (1:1),
 * and not a pair the user rejected.
 */
class TransactionMatcher(private val zone: ZoneId = ZoneId.of("Asia/Kolkata")) {

    companion object {
        const val RULES_VERSION = "dedup-v1"
        const val AUTO_MERGE_AT = 8
        const val SUGGEST_AT = 5
        /** Auto-merge only if the best beats the runner-up by this much; otherwise ask. */
        const val AMBIGUITY_MARGIN = 3
        private const val DECISIVE = 100
        val HARD_LIMIT: Duration = Duration.ofDays(7)
        private val REALTIME_WINDOW: Duration = Duration.ofMinutes(10)
        private val MANUAL_WINDOW: Duration = Duration.ofDays(2)
        private val REALTIME_SOURCES = setOf("sms", "gmail", "whatsapp")
    }

    fun decide(incoming: Transaction, candidates: List<MatchCandidate>, rejectedWith: Set<UUID> = emptySet()): MatchDecision {
        val scored = candidates
            .filter { eligible(incoming, it, rejectedWith) }
            .mapNotNull { c -> score(incoming, c.transaction)?.let { (s, why) -> Triple(c.transaction, s, why) } }
            .sortedWith(compareByDescending<Triple<Transaction, Int, String>> { it.second }
                .thenBy { abs(Duration.between(it.first.transactedAt, incoming.transactedAt).seconds) })
        val best = scored.firstOrNull() ?: return MatchDecision.None
        val runnerUp = scored.getOrNull(1)?.second
        val (target, score, reason) = best
        if (score < SUGGEST_AT) return MatchDecision.None

        val manual = incoming.sourceType == "manual" || target.sourceType == "manual"
        val ambiguous = runnerUp != null && score - runnerUp < AMBIGUITY_MARGIN
        return when {
            score >= DECISIVE && !ambiguous && !manual -> MatchDecision.AutoMerge(target, score, reason)
            score >= AUTO_MERGE_AT && !ambiguous && !manual -> MatchDecision.AutoMerge(target, score, reason)
            ambiguous -> MatchDecision.Suggest(target, score, "$reason; another similar transaction exists")
            else -> MatchDecision.Suggest(target, score, if (manual) "$reason; manual entries are only suggested" else reason)
        }
    }

    private fun eligible(incoming: Transaction, c: MatchCandidate, rejectedWith: Set<UUID>): Boolean {
        val t = c.transaction
        return t.id != incoming.id && t.userId == incoming.userId && t.type == incoming.type &&
            t.amount.compareTo(incoming.amount) == 0 && t.currency == incoming.currency &&
            t.sourceType != incoming.sourceType && incoming.sourceType !in c.mergedSources &&
            t.id !in rejectedWith &&
            // Cash has no bank record to duplicate (AOS-204); its ATM withdrawal is a transfer (D-42).
            t.paymentMethod != PaymentMethod.CASH && incoming.paymentMethod != PaymentMethod.CASH
    }

    /** @return (score, reason), or null when a hard rule says "different payments". */
    private fun score(a: Transaction, b: Transaction): Pair<Int, String>? {
        if (a.accountLast4 != null && b.accountLast4 != null && a.accountLast4 != b.accountLast4) return null
        if (a.paymentRef != null && b.paymentRef != null) {
            return if (a.paymentRef == b.paymentRef) DECISIVE to "same payment reference" else null
        }
        val window = timing(a, b) ?: return null
        var score = 4
        val why = mutableListOf("same amount")
        if (a.accountLast4 != null && a.accountLast4 == b.accountLast4) { score += 3; why += "same account" }
        if (a.upiVpa != null && a.upiVpa.equals(b.upiVpa, ignoreCase = true)) { score += 5; why += "same UPI ID" }
        score += if (window.sameDay) 3 else 1
        why += if (window.sameDay) "same day" else "within ${window.label}"
        val merchant = (2 * similarity(a.merchantName, b.merchantName)).roundToInt()
        if (merchant > 0) { score += merchant; why += "similar merchant" }
        return score to why.joinToString(", ")
    }

    private data class Window(val sameDay: Boolean, val label: String)

    /** Payment-type windows: statements carry dates only and cards post days later. */
    private fun timing(a: Transaction, b: Transaction): Window? {
        val gap = Duration.between(a.transactedAt, b.transactedAt).abs()
        if (gap > HARD_LIMIT) return null
        val dayA = a.transactedAt.atZone(zone).toLocalDate()
        val dayB = b.transactedAt.atZone(zone).toLocalDate()
        val sameDay = dayA == dayB
        val statement = listOf(a, b).firstOrNull { it.sourceType == "statement" }
        if (statement == null) {
            val bothRealtime = a.sourceType in REALTIME_SOURCES && b.sourceType in REALTIME_SOURCES
            val limit = if (bothRealtime) REALTIME_WINDOW else MANUAL_WINDOW
            return if (gap <= limit) Window(sameDay = bothRealtime || sameDay, label = if (bothRealtime) "10 minutes" else "2 days") else null
        }
        val other = if (statement === a) b else a
        // Positive: the statement row is dated after the other record.
        val days = ChronoUnit.DAYS.between(other.transactedAt.atZone(zone).toLocalDate(), statement.transactedAt.atZone(zone).toLocalDate())
        val methods = setOfNotNull(a.paymentMethod, b.paymentMethod)
        val ok = when {
            methods.any { it == PaymentMethod.CARD_DEBIT || it == PaymentMethod.CARD_CREDIT } -> days in -1..3
            PaymentMethod.NETBANKING in methods -> days in -2..2
            else -> days in -1..1 // UPI / IMPS / unknown
        }
        return if (ok) Window(sameDay, "the statement window") else null
    }

    private fun similarity(x: String?, y: String?): Double {
        val a = tokens(x)
        val b = tokens(y)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return (a intersect b).size.toDouble() / (a union b).size
    }

    private fun tokens(s: String?): Set<String> =
        s.orEmpty().lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()
}
