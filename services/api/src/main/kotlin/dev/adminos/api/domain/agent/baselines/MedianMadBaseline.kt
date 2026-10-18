package dev.adminos.api.domain.agent.baselines

import java.math.BigDecimal
import kotlin.math.ln

/** Where the median and MAD are taken: on rupees, or on log rupees (spending varies by multiples). */
enum class AmountScale {
    LINEAR,
    /** ln(amount): a ₹1,200 shop that is sometimes ₹400 and sometimes ₹3,600 varies by ×3 either way, not by ±₹2,400. */
    LOG
}

/**
 * The robust baseline (AOS-713, research §10): the median of earlier amounts and their median
 * absolute deviation (MAD). An amount is unusual when its modified z-score
 * `0.6745 · (x − median) / MAD` is above [threshold] plus one [toleranceStep] per tolerance step
 * (Iglewicz & Hoaglin, 1993: 3.5). The median and MAD aren't pulled up by the very outliers we look for.
 *
 * On the [AmountScale.LOG] scale (the default, chosen on the eval set: D-73) x is ln(amount), because a
 * person's spending at one place is roughly log-normal: big orders are multiples of the usual, not a
 * fixed number of rupees more.
 *
 * Spread when the MAD can't say:
 * - MAD = 0 (more than half the amounts identical): the mean absolute deviation, scaled by 1.2533
 *   (Iglewicz & Hoaglin's own fallback);
 * - and never less than [minRelativeSpread] (10%) of the median, so a merchant that always charges
 *   ₹649 doesn't alert on ₹700, and nothing divides by zero.
 */
class MedianMadBaseline(
    override val minHistory: Int = 5,
    private val threshold: Double = 3.5,
    private val toleranceStep: Double = 1.0,
    private val minRelativeSpread: Double = 0.10,
    private val scale: AmountScale = AmountScale.LOG
) : BaselineModel {
    init {
        require(minHistory >= 3) { "A median/MAD baseline needs at least 3 amounts" }
        require(threshold > 0 && toleranceStep >= 0) { "Thresholds are positive" }
        require(minRelativeSpread > 0) { "The minimum spread is positive" }
    }

    override val name = "median_mad_" + scale.name.lowercase()

    override fun fit(history: List<BigDecimal>): Baseline? {
        if (history.size < minHistory) return null
        if (scale == AmountScale.LOG && history.any { it.signum() <= 0 }) return null // ln needs positive amounts
        val points = history.map { it.onScale() }
        val median = quantile(points, 0.5)
        val deviations = points.map { kotlin.math.abs(it - median) }
        val mad = quantile(deviations, 0.5)
        val robust = if (mad > 0) mad / CONSISTENCY else MEAN_AD_FACTOR * deviations.average()
        return Fitted(history.size, AmountStats.usualRange(history), median, robust.coerceAtLeast(minimumSpread(AmountStats.median(history))))
    }

    private fun BigDecimal.onScale(): Double = when (scale) {
        AmountScale.LINEAR -> toDouble()
        AmountScale.LOG -> ln(toDouble())
    }

    /** 10% of the median in rupees, or ln(1.1) on the log scale: the same "10% more is never unusual". */
    private fun minimumSpread(median: BigDecimal): Double = when (scale) {
        AmountScale.LINEAR -> minRelativeSpread * median.toDouble()
        AmountScale.LOG -> ln(1 + minRelativeSpread)
    }

    private inner class Fitted(
        override val sampleSize: Int,
        override val usualRange: UsualRange,
        private val median: Double,
        /** Robust standard deviation; positive unless every amount (and so the median) is zero. */
        private val spread: Double
    ) : Baseline {
        override fun distance(amount: BigDecimal): Double {
            if (scale == AmountScale.LOG && amount.signum() <= 0) return Double.NEGATIVE_INFINITY
            val x = amount.onScale()
            return if (spread > 0) (x - median) / spread else if (x > median) Double.POSITIVE_INFINITY else 0.0
        }

        override fun isUnusual(amount: BigDecimal, tolerance: ToleranceLevel): Boolean =
            distance(amount) > threshold + toleranceStep * tolerance.steps
    }

    private companion object {
        /** MAD / 0.6745 estimates the standard deviation of normal data, so z = 0.6745·(x − median)/MAD. */
        const val CONSISTENCY = 0.6745
        /** Mean absolute deviation × 1.253314 estimates the standard deviation (used when MAD = 0). */
        const val MEAN_AD_FACTOR = 1.253314

        /** Type-7 quantile of doubles (the same rule as [AmountStats.quantile]). */
        fun quantile(values: List<Double>, q: Double): Double {
            val sorted = values.sorted()
            val position = q * (sorted.size - 1)
            val lower = position.toInt()
            val upper = minOf(lower + 1, sorted.size - 1)
            return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
        }
    }
}
