package dev.adminos.api.domain.financial.grouping

import kotlin.math.max
import kotlin.math.min

/**
 * How alike two cleaned merchant names are, 0..1 (AOS-709, D-75). A strategy, so the eval can measure
 * each one on the same pairs and a better one (e.g. a learned or phonetic one) is one more class.
 */
interface MerchantSimilarity {
    val name: String
    fun score(a: CleanName, b: CleanName): Double
}

/**
 * Jaro-Winkler on the names without spaces (Winkler, 1990): good at spelling variants and run-together
 * words ("Swigy" / "Swiggy", "Big Basket" / "BigBasket"); it rewards a shared start.
 */
object JaroWinklerSimilarity : MerchantSimilarity {
    override val name = "jaro_winkler"
    private const val PREFIX_SCALE = 0.1
    private const val MAX_PREFIX = 4

    override fun score(a: CleanName, b: CleanName): Double = jaroWinkler(a.compact, b.compact)

    fun jaroWinkler(s: String, t: String): Double {
        val jaro = jaro(s, t)
        val prefix = s.zip(t).take(MAX_PREFIX).takeWhile { (x, y) -> x == y }.size
        return jaro + prefix * PREFIX_SCALE * (1 - jaro)
    }

    fun jaro(s: String, t: String): Double {
        if (s == t) return 1.0
        if (s.isEmpty() || t.isEmpty()) return 0.0
        val window = max(0, max(s.length, t.length) / 2 - 1)
        val sMatched = BooleanArray(s.length)
        val tMatched = BooleanArray(t.length)
        var matches = 0
        for (i in s.indices) {
            val from = max(0, i - window)
            val to = min(t.length - 1, i + window)
            for (j in from..to) {
                if (!tMatched[j] && s[i] == t[j]) {
                    sMatched[i] = true; tMatched[j] = true; matches++
                    break
                }
            }
        }
        if (matches == 0) return 0.0
        val sChars = s.filterIndexed { i, _ -> sMatched[i] }
        val tChars = t.filterIndexed { j, _ -> tMatched[j] }
        val transpositions = sChars.zip(tChars).count { (x, y) -> x != y } / 2.0
        val m = matches.toDouble()
        return (m / s.length + m / t.length + (m - transpositions) / m) / 3
    }
}

/**
 * Token-set ratio (as in the common fuzzy-matching libraries): the shared words against each side's
 * words, by edit-distance ratio, best of three. Good at word order and extra words; on its own it
 * scores a name that is a subset of another ("Amazon" / "Amazon Pay") as identical, which the matcher
 * guards against.
 */
object TokenSetSimilarity : MerchantSimilarity {
    override val name = "token_set"

    override fun score(a: CleanName, b: CleanName): Double {
        val ta = a.tokens.toSortedSet()
        val tb = b.tokens.toSortedSet()
        val common = (ta intersect tb).joinToString(" ")
        val restA = listOf(common, (ta - tb).joinToString(" ")).filter { it.isNotEmpty() }.joinToString(" ")
        val restB = listOf(common, (tb - ta).joinToString(" ")).filter { it.isNotEmpty() }.joinToString(" ")
        return maxOf(ratio(common, restA), ratio(common, restB), ratio(restA, restB))
    }

    /** 1 − edit distance / combined length… as (len − distance·2)/len over both strings: the usual "ratio". */
    fun ratio(s: String, t: String): Double {
        if (s.isEmpty() && t.isEmpty()) return 1.0
        if (s.isEmpty() || t.isEmpty()) return 0.0
        val total = s.length + t.length
        return (total - indelDistance(s, t)).toDouble() / total
    }

    /** Insertions and deletions only (each substitution costs 2), the distance behind the ratio. */
    private fun indelDistance(s: String, t: String): Int {
        var previous = IntArray(t.length + 1) { it }
        for (i in 1..s.length) {
            val current = IntArray(t.length + 1)
            current[0] = i
            for (j in 1..t.length) {
                current[j] = if (s[i - 1] == t[j - 1]) previous[j - 1] else 1 + min(previous[j], current[j - 1])
            }
            previous = current
        }
        return previous[t.length]
    }
}
