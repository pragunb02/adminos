package dev.adminos.api.domain.financial.grouping

import kotlin.math.max
import kotlin.math.min

/** One merchant as the user's payments show it (after any aliases they already confirmed). */
data class MerchantProfile(
    /** The name the user sees, e.g. "NETFLIX.COM" or the catalogue brand "Netflix". */
    val name: String,
    /** [dev.adminos.api.domain.financial.MerchantKey] of [name]. */
    val key: String,
    val clean: CleanName,
    val payments: Int,
    /** UPI IDs paid under this name, lower-cased. */
    val upiIds: Set<String> = emptySet(),
    /** The catalogue brand when [name] is one (D-44). */
    val brand: String? = null,
    /** Positive evidence it is a business (card payments, a gateway, a business word, or a brand); else it may be a person. */
    val business: Boolean = brand != null
) {
    init {
        require(name.isNotBlank() && key.isNotBlank()) { "A merchant has a name and a key" }
        require(payments >= 0) { "Payments are counted from zero" }
    }
}

/** Why two names were proposed as one merchant, in words the Review card shows. */
enum class MatchEvidence(val text: String) {
    SAME_CLEAN_NAME("the same name once cleaned"),
    SHARED_UPI_ID("paid to the same UPI ID"),
    SIMILAR_NAME("the names look alike"),
    KNOWN_BRAND("one is a known brand")
}

data class MerchantMatch(val score: Double, val evidence: Set<MatchEvidence>) {
    init {
        require(score in 0.0..1.0) { "A match score is 0..1" }
        require(evidence.any { it != MatchEvidence.KNOWN_BRAND }) { "A brand alone is a hint, not a match" }
    }
}

/**
 * Decides whether two merchant names are one merchant (AOS-709, D-75). It only **proposes**: the user
 * confirms in Review. Precision comes first (a wrong merge hides a real duplicate), so:
 * - **evidence** is one of: the same cleaned name; a shared UPI ID that isn't a gateway's; or names
 *   that are spelling variants by [similarity] (≥ [threshold]) with the same first [prefix] letters;
 * - **spelling variants** are judged word by word when both have the same number of words ("Amazon
 *   Prime" vs "Amazon Pay" differ in a word, "Ramesh" vs "Rajesh" in its start), and as run-together
 *   names otherwise, only when the shorter is at least [minLengthRatio] of the longer ("Amazon" is not
 *   "Amazon Pay");
 * - **people:** look-alike names count only when one side is a business; two people's names need a
 *   shared UPI ID or the same cleaned name ("Amit Patel" / "Amita Patel" are two people);
 * - **vetoes:** two different catalogue brands are never one merchant, and a name with look-alike
 *   letters from another alphabet or invisible characters is never proposed (it could pose as another);
 */
class MerchantMatcher(
    val similarity: MerchantSimilarity = JaroWinklerSimilarity,
    private val threshold: Double = 0.92,
    private val prefix: Int = 3,
    private val minLengthRatio: Double = 0.8
) {
    init {
        require(threshold in 0.0..1.0 && minLengthRatio in 0.0..1.0) { "Thresholds are 0..1" }
        require(prefix >= 0) { "A prefix is never negative" }
    }

    fun match(a: MerchantProfile, b: MerchantProfile): MerchantMatch? {
        if (a.key == b.key) return null
        if (a.clean.confusable || b.clean.confusable) return null // look-alike letters or invisible characters: never merged
        if (a.brand != null && b.brand != null && !a.brand.equals(b.brand, ignoreCase = true)) return null
        val score = similarity.score(a.clean, b.clean)
        val evidence = buildSet {
            if (a.clean.compact == b.clean.compact) add(MatchEvidence.SAME_CLEAN_NAME)
            if (sharesUpiId(a, b)) add(MatchEvidence.SHARED_UPI_ID)
            // People's names are only merged on a shared UPI ID or the same cleaned name: "Amit" and "Amita" are two people.
            if ((a.business || b.business) && looksAlike(a.clean, b.clean)) add(MatchEvidence.SIMILAR_NAME)
        }
        if (evidence.isEmpty()) return null
        val hint = if ((a.brand == null) != (b.brand == null)) setOf(MatchEvidence.KNOWN_BRAND) else emptySet()
        val strength = when {
            MatchEvidence.SAME_CLEAN_NAME in evidence -> 1.0
            MatchEvidence.SHARED_UPI_ID in evidence -> max(score, SHARED_UPI_SCORE)
            else -> score
        }
        return MerchantMatch(strength.coerceIn(0.0, 1.0), evidence + hint)
    }

    /** Spelling variants: word by word when the word counts agree, else as run-together names. */
    fun looksAlike(a: CleanName, b: CleanName): Boolean {
        if (!sameStart(a.compact, b.compact)) return false
        if (a.tokens.size == b.tokens.size) {
            return a.tokens.zip(b.tokens).all { (x, y) -> x == y || (sameStart(x, y) && similarity.score(CleanName(listOf(x)), CleanName(listOf(y))) >= threshold) }
        }
        val ratio = min(a.compact.length, b.compact.length).toDouble() / max(a.compact.length, b.compact.length)
        return ratio >= minLengthRatio && similarity.score(a, b) >= threshold
    }

    private fun sameStart(x: String, y: String) = x.take(prefix) == y.take(prefix)

    /** Names that can match share this unless they share a UPI ID: the grouping compares only within it. */
    fun blockKey(name: CleanName): String = name.compact.take(prefix)

    private fun sharesUpiId(a: MerchantProfile, b: MerchantProfile): Boolean =
        (a.upiIds intersect b.upiIds).any { id -> MerchantNameCleaner.GATEWAYS.none { id.substringBefore('@').contains(it) } }

    companion object {
        /** A shared UPI ID is strong evidence on its own (the same payee), whatever the names say. */
        const val SHARED_UPI_SCORE = 0.95
    }
}
