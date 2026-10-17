package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionCategory.FOOD
import dev.adminos.api.domain.financial.TransactionCategory.HEALTH
import dev.adminos.api.domain.financial.learning.LearningFixtures.example
import dev.adminos.api.domain.financial.learning.LearningFixtures.input
import dev.adminos.api.domain.financial.learning.LearningFixtures.localShopExamples
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CalibrationTest {

    private fun heldOut(p: Double, correct: Boolean, easy: Boolean = false): HeldOutPrediction {
        val label = if (correct) FOOD else HEALTH
        return HeldOutPrediction(example("x", label).copy(easyForRules = easy), mapOf(FOOD to p, HEALTH to 1 - p))
    }

    @Test
    fun `temperature 1 changes nothing and the top category never changes`() {
        val p = mapOf(FOOD to 0.7, HEALTH to 0.3)
        assertSame(p, Calibration.scale(p, 1.0))
        val soft = Calibration.scale(p, 3.0)
        val sharp = Calibration.scale(p, 0.5)
        assertTrue(soft.getValue(FOOD) in 0.5..0.7, "softer: $soft")
        assertTrue(sharp.getValue(FOOD) > 0.7, "sharper: $sharp")
        assertTrue(abs(soft.values.sum() - 1) < 1e-9)
        assertFailsWith<IllegalArgumentException> { Calibration.scale(p, 0.0) }
    }

    @Test
    fun `an over-confident model gets a temperature above 1, a timid one below`() {
        val overConfident = List(40) { heldOut(0.99, correct = it % 2 == 0) } // 99% sure, right half the time
        assertTrue(Calibration.fitTemperature(overConfident) > 1.0)
        val timid = List(40) { heldOut(0.6, correct = true) } // 60% sure, always right
        assertTrue(Calibration.fitTemperature(timid) < 1.0)
        assertEquals(1.0, Calibration.fitTemperature(emptyList()))
    }

    @Test
    fun `fitting lowers the negative log-likelihood`() {
        val overConfident = List(40) { heldOut(0.99, correct = it % 2 == 0) }
        val t = Calibration.fitTemperature(overConfident)
        assertTrue(Calibration.nll(overConfident, t) < Calibration.nll(overConfident, 1.0))
    }

    @Test
    fun `the expected calibration error is the weighted gap between confidence and accuracy`() {
        assertNull(Calibration.expectedCalibrationError(emptyList()))
        assertEquals(0.0, Calibration.expectedCalibrationError(listOf(1.0 to true, 1.0 to true)))
        // One bin at 0.9 confidence, right half the time: gap 0.4.
        assertEquals(0.4, Calibration.expectedCalibrationError(listOf(0.9 to true, 0.9 to false)) ?: -1.0, 1e-9)
        assertEquals(0.1235, Calibration.round4(0.12345))
    }

    @Test
    fun `a calibrated model keeps the inner answer and cues and re-scales the confidence`() {
        val inner = SoftmaxRegressionTrainer().train(localShopExamples())
        val raw = inner.predict(input("Mahadev Medicos"))
        val calibrated = CalibratedCategoryModel(inner, 2.0).predict(input("Mahadev Medicos"))
        assertEquals(raw.category, calibrated.category)
        assertEquals(raw.cues, calibrated.cues)
        assertTrue(calibrated.confidence < raw.confidence)
        assertEquals(inner.algorithm, CalibratedCategoryModel(inner, 2.0).algorithm)
        assertFailsWith<IllegalArgumentException> { CalibratedCategoryModel(inner, -1.0) }
    }

    @Test
    fun `cross-validation predicts every example with a model that never saw it`() {
        val examples = localShopExamples()
        val heldOut = CategoryModelFitter(SoftmaxRegressionTrainer()).crossValidate(examples)
        assertEquals(examples.map { it.key }.sorted(), heldOut.map { it.example.key }.sorted())
        assertTrue(heldOut.count { it.correct } >= examples.size * 3 / 4, "held-out accuracy ${heldOut.count { it.correct }}/${examples.size}")
    }

    @Test
    fun `a fold whose training rows have one category is skipped`() {
        // Two rows, two folds: each fold's training part has a single category.
        val examples = listOf(example("a", FOOD, key = "k0"), example("b", HEALTH, key = "k1"))
        assertEquals(emptyList(), CategoryModelFitter(SoftmaxRegressionTrainer(), folds = 2).crossValidate(examples))
        assertFailsWith<IllegalArgumentException> { CategoryModelFitter(SoftmaxRegressionTrainer(), folds = 1) }
    }

    @Test
    fun `fitting returns calibrated held-out answers and a final model trained on everything`() {
        val fitted = CategoryModelFitter(SoftmaxRegressionTrainer()).fit(localShopExamples())
        assertEquals(localShopExamples().size, fitted.heldOut.size)
        assertTrue(fitted.model.temperature > 0)
        assertEquals(TransactionCategory.HEALTH, fitted.model.predict(input("Mahadev Medicos")).category)
    }

    @Test
    fun `with few rows like the ones it will be asked about, calibration uses every held-out answer`() {
        val fitted = CategoryModelFitter(SoftmaxRegressionTrainer()).fit(localShopExamples(perKind = 2))
        assertTrue(fitted.heldOut.size < CategoryModelFitter.MIN_CALIBRATION_ROWS)
        assertTrue(fitted.model.temperature > 0)
    }

    @Test
    fun `held-out answers name their guess and whether it was right`() {
        val h = heldOut(0.8, correct = true)
        assertEquals(FOOD, h.guess); assertEquals(0.8, h.confidence); assertTrue(h.correct)
    }
}
