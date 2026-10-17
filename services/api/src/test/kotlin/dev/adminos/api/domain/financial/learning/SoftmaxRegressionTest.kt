package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.learning.LearningFixtures.example
import dev.adminos.api.domain.financial.learning.LearningFixtures.input
import dev.adminos.api.domain.financial.learning.LearningFixtures.localShopExamples
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SoftmaxRegressionTest {

    private val trainer = SoftmaxRegressionTrainer()
    private val model = trainer.train(localShopExamples())

    @Test
    fun `it learns the kind of shop from words it has seen in other payees' names`() {
        assertEquals(TransactionCategory.HEALTH, model.predict(input("Mahadev Medicos")).category)
        assertEquals(TransactionCategory.SHOPPING, model.predict(input("Lucky Kirana Stores")).category)
        assertEquals(TransactionCategory.EDUCATION, model.predict(input("Success Classes")).category)
        assertEquals(TransactionCategory.FOOD, model.predict(input("Guddu Chai Point")).category)
    }

    @Test
    fun `the cues name the word that decided it`() {
        val guess = model.predict(input("Mahadev Medicos"))
        assertEquals("medicos", guess.cues.first())
        assertTrue(guess.cues.size <= SoftmaxRegressionModel.MAX_CUES)
    }

    @Test
    fun `an unseen merchant gets a low-confidence guess, not an error`() {
        val guess = model.predict(input("Zqxv Wpty"))
        assertTrue(guess.confidence < 0.5, "confidence ${guess.confidence}")
        val nothing = model.predict(CategoryInput(merchantName = null))
        assertTrue(nothing.confidence < 0.5)
        assertEquals(emptyList(), nothing.cues)
    }

    @Test
    fun `the distribution covers every category it knows and sums to 1`() {
        val p = model.distribution(input("Sai Medicos"))
        assertEquals(model.categories.toSet(), p.keys)
        assertTrue(abs(p.values.sum() - 1.0) < 1e-9)
    }

    @Test
    fun `training is deterministic`() {
        val again = trainer.train(localShopExamples())
        assertEquals(model.features, again.features)
        model.weights.indices.forEach { assertContentEquals(model.weights[it], again.weights[it]) }
        assertContentEquals(model.bias, again.bias)
    }

    @Test
    fun `heavier corrections outweigh lighter prior rows`() {
        val examples = listOf(
            example("Zoom Medicos", TransactionCategory.SHOPPING, ExampleOrigin.PRIOR, "prior:1"),
            example("Zoom Medicos", TransactionCategory.HEALTH, ExampleOrigin.CORRECTION, "user:1")
        )
        assertEquals(TransactionCategory.HEALTH, trainer.train(examples).predict(input("Zoom Medicos")).category)
    }

    @Test
    fun `it needs examples of at least two categories`() {
        assertFailsWith<IllegalArgumentException> { trainer.train(emptyList()) }
        val one = localShopExamples().filter { it.label == TransactionCategory.HEALTH }
        assertFailsWith<IllegalArgumentException> { trainer.train(one) }
    }

    @Test
    fun `the vocabulary is capped at the most frequent features`() {
        val small = SoftmaxRegressionTrainer(SoftmaxSettings(maxFeatures = 10)).train(localShopExamples())
        assertEquals(10, small.features.size)
        assertEquals(small.features.sorted(), small.features)
    }

    @Test
    fun `settings are validated`() {
        assertFailsWith<IllegalArgumentException> { SoftmaxSettings(epochs = 0) }
        assertFailsWith<IllegalArgumentException> { SoftmaxSettings(learningRate = 0.0) }
        assertFailsWith<IllegalArgumentException> { SoftmaxSettings(l2 = -1.0) }
        assertFailsWith<IllegalArgumentException> { SoftmaxSettings(maxFeatures = 0) }
    }

    @Test
    fun `a model can't be built from inconsistent parts`() {
        val c = listOf(TransactionCategory.FOOD, TransactionCategory.HEALTH)
        fun build(
            categories: List<TransactionCategory> = c,
            features: List<String> = listOf("w:a"),
            idf: DoubleArray = doubleArrayOf(1.0),
            bias: DoubleArray = doubleArrayOf(0.0, 0.0),
            weights: Array<FloatArray> = arrayOf(floatArrayOf(1f), floatArrayOf(0f))
        ) = SoftmaxRegressionModel(categories, features, idf, bias, weights)
        build() // valid
        assertFailsWith<IllegalArgumentException> { build(categories = listOf(TransactionCategory.FOOD)) }
        assertFailsWith<IllegalArgumentException> { build(categories = listOf(TransactionCategory.FOOD, TransactionCategory.TRANSFER)) }
        assertFailsWith<IllegalArgumentException> { build(features = emptyList(), idf = DoubleArray(0), weights = arrayOf(FloatArray(0), FloatArray(0))) }
        assertFailsWith<IllegalArgumentException> { build(features = listOf("w:a", "w:a"), idf = doubleArrayOf(1.0, 1.0)) }
        assertFailsWith<IllegalArgumentException> { build(idf = doubleArrayOf(0.0)) }
        assertFailsWith<IllegalArgumentException> { build(bias = doubleArrayOf(Double.NaN, 0.0)) }
        assertFailsWith<IllegalArgumentException> { build(weights = arrayOf(floatArrayOf(Float.POSITIVE_INFINITY), floatArrayOf(0f))) }
    }

    @Test
    fun `a guess never says Transfer and is a probability`() {
        assertFailsWith<IllegalArgumentException> { CategoryGuess(TransactionCategory.TRANSFER, 0.9) }
        assertFailsWith<IllegalArgumentException> { CategoryGuess(TransactionCategory.FOOD, 1.2) }
        assertFailsWith<IllegalArgumentException> { example("Self", TransactionCategory.TRANSFER) }
        assertFailsWith<IllegalArgumentException> { TrainingExample(" ", input("x"), TransactionCategory.FOOD, ExampleOrigin.PRIOR) }
    }
}
