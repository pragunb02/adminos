package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.TransactionCategory
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** One held-out answer: the model's probabilities for an example it was not trained on, and the true label. */
data class HeldOutPrediction(val example: TrainingExample, val distribution: Map<TransactionCategory, Double>) {
    val guess: TransactionCategory get() = distribution.maxBy { it.value }.key
    val confidence: Double get() = distribution.getValue(guess)
    val correct: Boolean get() = guess == example.label
}

/**
 * Temperature scaling (Guo et al., 2017) and the expected calibration error (AOS-707). Pure functions.
 * Temperature T > 1 softens over-confident probabilities, T < 1 sharpens timid ones; the order of categories never changes.
 */
object Calibration {
    private const val MIN_LOG_T = -3.0 // T ≈ 0.05
    private const val MAX_LOG_T = 3.0 // T ≈ 20
    private const val SEARCH_STEPS = 60
    private const val FLOOR = 1e-12
    const val BINS = 10

    /** [p] re-scaled by temperature [t]: softmax(z / t) computed from the probabilities, p^(1/t) normalised. */
    fun scale(p: Map<TransactionCategory, Double>, t: Double): Map<TransactionCategory, Double> {
        require(t > 0 && t.isFinite()) { "A temperature is positive, was $t" }
        if (t == 1.0) return p
        val logs = p.mapValues { (_, v) -> ln(max(v, FLOOR)) / t }
        val top = logs.values.max()
        val e = logs.mapValues { (_, v) -> exp(v - top) }
        val sum = e.values.sum()
        return e.mapValues { (_, v) -> v / sum }
    }

    /** The temperature minimising the negative log-likelihood of [heldOut]; 1 (no change) when there is nothing to fit. */
    fun fitTemperature(heldOut: List<HeldOutPrediction>): Double {
        if (heldOut.isEmpty()) return 1.0
        // Golden-section search on log T: NLL is unimodal in T for a fixed set of logits.
        val ratio = (sqrt(5.0) - 1) / 2
        var lo = MIN_LOG_T
        var hi = MAX_LOG_T
        repeat(SEARCH_STEPS) {
            val a = hi - ratio * (hi - lo)
            val b = lo + ratio * (hi - lo)
            if (nll(heldOut, exp(a)) <= nll(heldOut, exp(b))) hi = b else lo = a
        }
        return exp((lo + hi) / 2)
    }

    /** Mean negative log-likelihood of the true labels at temperature [t]. */
    fun nll(heldOut: List<HeldOutPrediction>, t: Double): Double =
        heldOut.sumOf { h -> -ln(max(scale(h.distribution, t)[h.example.label] ?: 0.0, FLOOR)) } / heldOut.size

    /**
     * Expected calibration error over [BINS] equal-width confidence bins: the average gap between confidence and
     * accuracy, weighted by how many answers fall in each bin. 0 is perfectly calibrated; null when nothing to measure.
     */
    fun expectedCalibrationError(answers: List<Pair<Double, Boolean>>): Double? {
        if (answers.isEmpty()) return null
        val bins = answers.groupBy { (confidence, _) -> minOf((confidence * BINS).toInt(), BINS - 1) }
        return bins.values.sumOf { bin ->
            val confidence = bin.sumOf { it.first } / bin.size
            val accuracy = bin.count { it.second }.toDouble() / bin.size
            abs(confidence - accuracy) * bin.size
        } / answers.size
    }

    /** Rounds a score to 4 places for storage and reports. */
    fun round4(x: Double): Double = BigDecimal.valueOf(x).setScale(4, RoundingMode.HALF_UP).toDouble()
}

/** A model whose probabilities are re-scaled by a fitted [temperature]; the answer and its cues are the inner model's. */
class CalibratedCategoryModel(val inner: CategoryModel, val temperature: Double) : CategoryModel {
    init {
        require(temperature > 0 && temperature.isFinite()) { "A temperature is positive, was $temperature" }
    }

    override val algorithm: String get() = inner.algorithm
    override val categories: List<TransactionCategory> get() = inner.categories

    override fun distribution(input: CategoryInput) = Calibration.scale(inner.distribution(input), temperature)

    override fun predict(input: CategoryInput): CategoryGuess {
        val raw = inner.predict(input)
        val confidence = distribution(input).getValue(raw.category).coerceIn(0.0, 1.0)
        return raw.copy(confidence = confidence)
    }
}

/** A calibrated model and what cross-validation said about it before it was trained on everything. */
data class FittedModel(val model: CalibratedCategoryModel, val heldOut: List<HeldOutPrediction>)

/**
 * Trains a model and calibrates it (AOS-707):
 * 1. k-fold cross-validation: each example is predicted by a model that never saw it (folds by the example's key, so
 *    the split is stable between runs);
 * 2. a temperature fitted on those held-out answers (the rows like the ones it will be asked about);
 * 3. the final model, trained on every example, with that temperature.
 * The held-out answers are returned (already calibrated) so the caller can judge the model before using it.
 */
class CategoryModelFitter(private val trainer: CategoryModelTrainer, private val folds: Int = DEFAULT_FOLDS) {
    init {
        require(folds >= 2) { "Cross-validation needs at least two folds" }
    }

    fun fit(examples: List<TrainingExample>): FittedModel {
        val raw = crossValidate(examples)
        val temperature = Calibration.fitTemperature(calibrationSet(raw))
        val model = CalibratedCategoryModel(trainer.train(examples), temperature)
        return FittedModel(model, raw.map { it.copy(distribution = Calibration.scale(it.distribution, temperature)) })
    }

    /** Held-out answers for every example whose fold could be trained without it (a fold needs two categories). */
    fun crossValidate(examples: List<TrainingExample>): List<HeldOutPrediction> {
        val byFold = examples.groupBy { Math.floorMod(it.key.hashCode(), folds) }
        return byFold.toSortedMap().flatMap { (fold, held) ->
            val rest = examples.filter { Math.floorMod(it.key.hashCode(), folds) != fold }
            if (rest.map { it.label }.distinct().size < 2) emptyList()
            else trainer.train(rest).let { m -> held.map { HeldOutPrediction(it, m.distribution(it.input)) } }
        }
    }

    /**
     * The held-out answers the temperature is fitted on: those the rules could not decide, because those are the only
     * rows the model is asked about in use. With fewer than [MIN_CALIBRATION_ROWS] of them, every held-out answer.
     */
    private fun calibrationSet(heldOut: List<HeldOutPrediction>): List<HeldOutPrediction> =
        heldOut.filterNot { it.example.easyForRules }.takeIf { it.size >= MIN_CALIBRATION_ROWS } ?: heldOut

    companion object {
        const val DEFAULT_FOLDS = 5
        const val MIN_CALIBRATION_ROWS = 20
    }
}
