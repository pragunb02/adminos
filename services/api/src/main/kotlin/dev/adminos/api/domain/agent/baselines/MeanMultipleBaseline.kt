package dev.adminos.api.domain.agent.baselines

import java.math.BigDecimal

/**
 * Today's rule (AOS-505, D-53), kept as the last fallback and as the eval's "old" model: an amount is
 * unusual when it is more than [multiple] times the mean of earlier amounts, plus one per tolerance step.
 */
class MeanMultipleBaseline(
    override val minHistory: Int = 3,
    private val multiple: Int = 3
) : BaselineModel {
    init {
        require(minHistory >= 1) { "A mean needs at least one amount" }
        require(multiple >= 1) { "The multiple is at least 1" }
    }

    override val name = "mean_multiple"

    override fun fit(history: List<BigDecimal>): Baseline? {
        if (history.size < minHistory) return null
        return Fitted(history.size, AmountStats.usualRange(history), AmountStats.mean(history))
    }

    private inner class Fitted(
        override val sampleSize: Int,
        override val usualRange: UsualRange,
        private val mean: BigDecimal
    ) : Baseline {
        override fun distance(amount: BigDecimal): Double =
            if (mean.signum() > 0) amount.toDouble() / mean.toDouble() else 0.0

        override fun isUnusual(amount: BigDecimal, tolerance: ToleranceLevel): Boolean =
            mean.signum() > 0 && amount > mean.multiply(BigDecimal(multiple + tolerance.steps))
    }
}
