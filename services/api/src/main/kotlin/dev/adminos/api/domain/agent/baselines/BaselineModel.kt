package dev.adminos.api.domain.agent.baselines

import dev.adminos.api.domain.common.formatInr
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * How far a user has asked AdminOS to relax one rule at one merchant (AOS-713): 0 is the default,
 * each dismissal adds a step, each confirmation takes one away, never beyond [MAX]. Each model turns
 * the steps into its own threshold, so a real fraud far above normal still alerts.
 */
@JvmInline
value class ToleranceLevel(val steps: Int) {
    init {
        require(steps in 0..MAX) { "A tolerance level is 0..$MAX steps, was $steps" }
    }

    /** One step further, clamped to 0..[MAX]. */
    fun moved(by: Int): ToleranceLevel = ToleranceLevel((steps + by).coerceIn(0, MAX))

    companion object {
        const val MAX = 3
        val DEFAULT = ToleranceLevel(0)
    }
}

/** The 25th–75th percentile of earlier amounts: what the alert text calls "usually". */
data class UsualRange(val low: BigDecimal, val high: BigDecimal) {
    init {
        require(low <= high) { "A usual range runs low to high" }
    }

    /** "₹350–₹700", or "₹649" when every earlier amount was the same. */
    fun text(): String = if (low.compareTo(high) == 0) low.formatInr() else "${low.formatInr()}–${high.formatInr()}"
}

/** What a model learnt from earlier amounts, and whether a new amount is out of line with it. */
interface Baseline {
    /** How many earlier amounts it was fitted on. */
    val sampleSize: Int
    val usualRange: UsualRange

    /** Whether [amount] is unusually **high** at [tolerance]; an amount at or below the usual is never unusual. */
    fun isUnusual(amount: BigDecimal, tolerance: ToleranceLevel): Boolean

    /** How far [amount] is from usual, for logs and the eval (a z-score or a multiple). */
    fun distance(amount: BigDecimal): Double
}

/**
 * A way to say what's usual from earlier amounts (AOS-713, D-73). Two implementations: the robust
 * [MedianMadBaseline] and today's [MeanMultipleBaseline], so the eval measures both through one seam
 * and a new model (seasonal, Beta-Bernoulli…) is one more class.
 */
interface BaselineModel {
    /** Stable name for logs and the eval report. */
    val name: String
    /** Fewer earlier amounts than this, and the model won't say what's usual. */
    val minHistory: Int

    /** @return null when [history] has fewer than [minHistory] amounts (too little to judge) */
    fun fit(history: List<BigDecimal>): Baseline?
}

/** Order statistics on amounts: exact (BigDecimal) for what the user reads, doubles for scores. */
internal object AmountStats {
    /** Linear-interpolated quantile (type 7, as R and NumPy default), [q] in 0..1, of a non-empty list. */
    fun quantile(values: List<BigDecimal>, q: Double): BigDecimal {
        require(values.isNotEmpty()) { "A quantile needs values" }
        require(q in 0.0..1.0) { "A quantile is between 0 and 1" }
        val sorted = values.sorted()
        val position = q * (sorted.size - 1)
        val lower = position.toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        val fraction = BigDecimal.valueOf(position - lower)
        return (sorted[lower] + (sorted[upper] - sorted[lower]).multiply(fraction)).setScale(2, RoundingMode.HALF_UP)
    }

    fun median(values: List<BigDecimal>): BigDecimal = quantile(values, 0.5)

    fun usualRange(values: List<BigDecimal>) = UsualRange(quantile(values, 0.25), quantile(values, 0.75))

    fun mean(values: List<BigDecimal>): BigDecimal {
        require(values.isNotEmpty()) { "A mean needs values" }
        return values.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(values.size), 2, RoundingMode.HALF_UP)
    }
}
