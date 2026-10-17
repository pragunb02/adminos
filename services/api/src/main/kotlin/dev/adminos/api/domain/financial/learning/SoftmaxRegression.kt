package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.TransactionCategory
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** How [SoftmaxRegressionTrainer] learns. The defaults were chosen on the golden set's cross-validation (AOS-707). */
data class SoftmaxSettings(
    val epochs: Int = 30,
    val learningRate: Double = 0.1,
    /** L2 penalty per update: keeps weights small, so rare n-grams don't dominate. */
    val l2: Double = 1e-3,
    /** The most frequent features kept; the rest are dropped (bounds memory and the stored size). */
    val maxFeatures: Int = 50_000,
    val seed: Long = 707L
) {
    init {
        require(epochs in 1..500) { "epochs is 1–500, was $epochs" }
        require(learningRate > 0 && learningRate.isFinite()) { "The learning rate is positive" }
        require(l2 >= 0 && l2.isFinite()) { "The L2 penalty is not negative" }
        require(maxFeatures in 1..SoftmaxRegressionModel.MAX_FEATURES) { "maxFeatures is 1–${SoftmaxRegressionModel.MAX_FEATURES}" }
    }
}

/**
 * Multinomial logistic regression over TF-IDF-weighted sparse features (AOS-707): written here rather than taken
 * from a library (see the ticket's comparison with Tribuo). Trained by AdaGrad SGD with an L2 penalty, in a
 * fixed order from [SoftmaxSettings.seed], so the same examples always give the same model.
 */
class SoftmaxRegressionTrainer(private val settings: SoftmaxSettings = SoftmaxSettings()) : CategoryModelTrainer {
    override val algorithm = SoftmaxRegressionModel.ALGORITHM

    override fun train(examples: List<TrainingExample>): SoftmaxRegressionModel {
        require(examples.isNotEmpty()) { "Nothing to learn from" }
        val categories = examples.map { it.label }.distinct().sorted()
        require(categories.size >= 2) { "A model needs at least two categories, got $categories" }

        val featureNames = examples.map { e -> CategoryFeatures.of(e.input).map { it.name } }
        val vocabulary = vocabularyOf(featureNames)
        val index = vocabulary.names.withIndex().associate { (i, name) -> name to i }
        val rows = featureNames.map { names -> SparseRow.of(names.mapNotNull { index[it] }, vocabulary.idf) }
        val targets = examples.map { categories.indexOf(it.label) }

        val weights = Array(categories.size) { DoubleArray(vocabulary.names.size) }
        val bias = DoubleArray(categories.size)
        fit(Problem(rows, targets, examples.map { it.weight }), weights, bias)

        return SoftmaxRegressionModel(
            categories = categories,
            features = vocabulary.names,
            idf = vocabulary.idf,
            bias = bias,
            weights = Array(weights.size) { k -> FloatArray(weights[k].size) { j -> weights[k][j].toFloat() } }
        )
    }

    private class Vocabulary(val names: List<String>, val idf: DoubleArray)

    private class Problem(val rows: List<SparseRow>, val targets: List<Int>, val weights: List<Double>)

    /** Keeps the [SoftmaxSettings.maxFeatures] most frequent features; idf = ln((1 + n) / (1 + df)) + 1. */
    private fun vocabularyOf(featureNames: List<List<String>>): Vocabulary {
        val df = HashMap<String, Int>()
        featureNames.forEach { names -> names.forEach { df.merge(it, 1, Int::plus) } }
        val kept = df.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(settings.maxFeatures).map { it.key }.sorted()
        val n = featureNames.size.toDouble()
        return Vocabulary(kept, DoubleArray(kept.size) { ln((1 + n) / (1 + df.getValue(kept[it]))) + 1 })
    }

    private fun fit(problem: Problem, weights: Array<DoubleArray>, bias: DoubleArray) {
        val k = bias.size
        val gradSqW = Array(k) { DoubleArray(weights[0].size) }
        val gradSqB = DoubleArray(k)
        val random = Random(settings.seed)
        val order = problem.rows.indices.toMutableList()
        repeat(settings.epochs) {
            order.shuffle(random)
            for (i in order) step(problem, i, weights, bias, gradSqW, gradSqB)
        }
    }

    /** One AdaGrad update on row [i]: gradient of the weighted cross-entropy, plus L2 on the weights it touches. */
    private fun step(problem: Problem, i: Int, weights: Array<DoubleArray>, bias: DoubleArray, gradSqW: Array<DoubleArray>, gradSqB: DoubleArray) {
        val row = problem.rows[i]
        val p = softmax(DoubleArray(bias.size) { c -> bias[c] + row.dot(weights[c]) })
        val w = problem.weights[i]
        for (c in bias.indices) {
            val g = w * (p[c] - if (c == problem.targets[i]) 1.0 else 0.0)
            gradSqB[c] += g * g
            bias[c] -= settings.learningRate * g / (sqrt(gradSqB[c]) + EPSILON)
            for (n in row.indices.indices) {
                val j = row.indices[n]
                val gj = g * row.values[n] + settings.l2 * weights[c][j]
                gradSqW[c][j] += gj * gj
                weights[c][j] -= settings.learningRate * gj / (sqrt(gradSqW[c][j]) + EPSILON)
            }
        }
    }

    private companion object {
        const val EPSILON = 1e-8
    }
}

/** A sparse, L2-normalised TF-IDF row: feature indices and their values. */
internal class SparseRow(val indices: IntArray, val values: DoubleArray) {
    fun dot(w: DoubleArray): Double {
        var s = 0.0
        for (n in indices.indices) s += w[indices[n]] * values[n]
        return s
    }

    fun dot(w: FloatArray): Double {
        var s = 0.0
        for (n in indices.indices) s += w[indices[n]] * values[n]
        return s
    }

    companion object {
        fun of(featureIndices: List<Int>, idf: DoubleArray): SparseRow {
            val distinct = featureIndices.distinct().sorted()
            val raw = DoubleArray(distinct.size) { idf[distinct[it]] }
            val norm = sqrt(raw.sumOf { it * it })
            return SparseRow(distinct.toIntArray(), if (norm == 0.0) raw else DoubleArray(raw.size) { raw[it] / norm })
        }
    }
}

/** Numerically stable softmax. */
internal fun softmax(logits: DoubleArray): DoubleArray {
    val max = logits.max()
    val e = DoubleArray(logits.size) { exp(logits[it] - max) }
    val sum = e.sum()
    return DoubleArray(e.size) { e[it] / sum }
}

/**
 * A trained softmax regression (AOS-707). Immutable; validates itself so a damaged stored model can't be built.
 * Inputs with no known feature fall back to the bias alone, the training prior, which is a low-confidence guess.
 */
class SoftmaxRegressionModel(
    override val categories: List<TransactionCategory>,
    val features: List<String>,
    val idf: DoubleArray,
    val bias: DoubleArray,
    val weights: Array<FloatArray>
) : CategoryModel {
    override val algorithm = ALGORITHM

    private val index: Map<String, Int> = features.withIndex().associate { (i, name) -> name to i }

    init {
        require(categories.size >= 2 && categories.distinct().size == categories.size) { "A model has two or more distinct categories" }
        require(TransactionCategory.TRANSFER !in categories) { "A model never answers Transfer (D-41)" }
        require(features.isNotEmpty() && features.size <= MAX_FEATURES) { "A model has 1–$MAX_FEATURES features" }
        require(index.size == features.size) { "Feature names are unique" }
        require(idf.size == features.size && idf.all { it.isFinite() && it > 0 }) { "Every feature has a positive idf" }
        require(bias.size == categories.size && bias.all { it.isFinite() }) { "Every category has a finite bias" }
        require(weights.size == categories.size && weights.all { w -> w.size == features.size && w.all { it.isFinite() } }) {
            "Every category has a finite weight per feature"
        }
    }

    override fun distribution(input: CategoryInput): Map<TransactionCategory, Double> {
        val p = softmax(logits(rowOf(CategoryFeatures.of(input))))
        return categories.withIndex().associate { (k, c) -> c to p[k] }
    }

    override fun predict(input: CategoryInput): CategoryGuess {
        val features = CategoryFeatures.of(input)
        val row = rowOf(features)
        val p = softmax(logits(row))
        val best = p.indices.maxBy { p[it] }
        return CategoryGuess(categories[best], p[best].coerceIn(0.0, 1.0), cuesFor(best, features, row))
    }

    private fun rowOf(features: List<Feature>): SparseRow = SparseRow.of(features.mapNotNull { index[it.name] }, idf)

    private fun logits(row: SparseRow) = DoubleArray(categories.size) { k -> bias[k] + row.dot(weights[k]) }

    /** The input's words that added most to category [k], strongest first; only words that pushed towards it. */
    private fun cuesFor(k: Int, features: List<Feature>, row: SparseRow): List<String> {
        val valueOf = row.indices.withIndex().associate { (n, j) -> j to row.values[n] }
        return features.mapNotNull { f -> f.cue?.let { cue -> index[f.name]?.let { j -> cue to weights[k][j] * valueOf.getValue(j) } } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, contributions) -> contributions.sum() }
            .filterValues { it > 0 }
            .entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(MAX_CUES).map { it.key }
    }

    companion object {
        const val ALGORITHM = "softmax-v1"
        const val MAX_FEATURES = 200_000
        const val MAX_CUES = 3
    }
}
