package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.learning.CategoryFeatures
import dev.adminos.api.domain.financial.learning.CategoryGuess
import dev.adminos.api.domain.financial.learning.CategoryInput
import dev.adminos.api.domain.financial.learning.CategoryModel
import dev.adminos.api.domain.financial.learning.CategoryModelTrainer
import dev.adminos.api.domain.financial.learning.TrainingExample
import kotlin.math.exp
import kotlin.math.ln

/**
 * The comparison model for AOS-707 (evaluation only, not shipped): Bernoulli-style multinomial naive Bayes over the
 * same features, with Laplace smoothing [alpha]. It is the simplest dependency-free alternative to the softmax
 * regression, and the report scores both on the same folds so the choice rests on evidence.
 */
class NaiveBayesTrainer(private val alpha: Double = 0.5) : CategoryModelTrainer {
    override val algorithm = "naive-bayes"

    override fun train(examples: List<TrainingExample>): CategoryModel {
        require(examples.isNotEmpty()) { "Nothing to learn from" }
        val categories = examples.map { it.label }.distinct().sorted()
        require(categories.size >= 2) { "Two categories or more" }
        val counts = categories.associateWith { HashMap<String, Double>() }
        val priors = categories.associateWith { c -> examples.filter { it.label == c }.sumOf { it.weight } }
        examples.forEach { e -> CategoryFeatures.of(e.input).forEach { f -> counts.getValue(e.label).merge(f.name, e.weight, Double::plus) } }
        val vocabulary = counts.values.flatMap { it.keys }.toSet()
        val totals = categories.associateWith { c -> counts.getValue(c).values.sum() }
        val total = priors.values.sum()
        return NaiveBayesModel(categories, vocabulary, alpha, counts, totals, priors.mapValues { ln(it.value / total) })
    }
}

private class NaiveBayesModel(
    override val categories: List<TransactionCategory>,
    private val vocabulary: Set<String>,
    private val alpha: Double,
    private val counts: Map<TransactionCategory, Map<String, Double>>,
    private val totals: Map<TransactionCategory, Double>,
    private val logPriors: Map<TransactionCategory, Double>
) : CategoryModel {
    override val algorithm = "naive-bayes"

    private fun logLikelihood(c: TransactionCategory, feature: String): Double =
        ln(((counts.getValue(c)[feature] ?: 0.0) + alpha) / (totals.getValue(c) + alpha * vocabulary.size))

    override fun distribution(input: CategoryInput): Map<TransactionCategory, Double> {
        val known = CategoryFeatures.of(input).map { it.name }.filter { it in vocabulary }
        val scores = categories.associateWith { c -> logPriors.getValue(c) + known.sumOf { logLikelihood(c, it) } }
        val top = scores.values.max()
        val e = scores.mapValues { exp(it.value - top) }
        val sum = e.values.sum()
        return e.mapValues { it.value / sum }
    }

    override fun predict(input: CategoryInput): CategoryGuess {
        val p = distribution(input)
        val best = p.maxBy { it.value }
        return CategoryGuess(best.key, best.value.coerceIn(0.0, 1.0))
    }
}
