package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionCategory.FOOD
import dev.adminos.api.domain.financial.TransactionCategory.OTHER
import dev.adminos.api.domain.financial.TransactionCategory.SHOPPING
import dev.adminos.api.domain.financial.TransactionCategory.TRANSPORT
import dev.adminos.api.domain.financial.TransactionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-511: the evaluator's arithmetic on small hand-made outcomes. */
class CategorizationEvaluatorTest {

    private fun outcome(
        id: String, expected: TransactionCategory, predicted: TransactionCategory,
        expectedSub: String? = null, predictedSub: String? = null, tags: Set<String> = emptySet(), source: String = "rules"
    ) = Outcome(
        LabelledCase(id, CaseInput("UPI-PAYEE $id", null, PaymentMethod.UPI, TransactionType.DEBIT), Label(expected, expectedSub), tags),
        Prediction(Label(predicted, predictedSub), source)
    )

    // FOOD: 2 expected, 3 predicted, 2 right. SHOPPING: 2 expected, 0 predicted. OTHER: 1 expected, 2 predicted, 1 right.
    private val sample = listOf(
        outcome("a", FOOD, FOOD, "cafe", "cafe", tags = setOf("card")),
        outcome("b", FOOD, FOOD, "cafe", "restaurant"),
        outcome("c", SHOPPING, FOOD, tags = setOf("card", "gateway")),
        outcome("d", SHOPPING, OTHER, source = "remembered"),
        outcome("e", OTHER, OTHER)
    )

    @Test
    fun `accuracy is the share of rows with the expected category`() {
        val r = CategorizationEvaluator.evaluate(sample)
        assertEquals(5, r.total); assertEquals(3, r.correct); assertEquals(0.6, r.accuracy)
    }

    @Test
    fun `coverage is the share not left as Other`() {
        assertEquals(0.6, CategorizationEvaluator.evaluate(sample).coverage)
    }

    @Test
    fun `precision recall and F1 follow their definitions per category`() {
        val r = CategorizationEvaluator.evaluate(sample)
        val food = r.scoreOf(FOOD)!!
        assertEquals(2, food.support); assertEquals(3, food.predicted); assertEquals(2, food.truePositives)
        assertEquals(0.6667, food.precision); assertEquals(1.0, food.recall); assertEquals(0.8, food.f1)
        val other = r.scoreOf(OTHER)!!
        assertEquals(0.5, other.precision); assertEquals(1.0, other.recall); assertEquals(0.6667, other.f1)
    }

    @Test
    fun `a category that is expected but never predicted scores zero, not an error`() {
        val shopping = CategorizationEvaluator.evaluate(sample).scoreOf(SHOPPING)!!
        assertEquals(0.0, shopping.precision); assertEquals(0.0, shopping.recall); assertEquals(0.0, shopping.f1)
    }

    @Test
    fun `categories neither expected nor predicted are left out, and macro F1 averages the expected ones`() {
        val r = CategorizationEvaluator.evaluate(sample)
        assertNull(r.scoreOf(TRANSPORT))
        assertEquals(listOf(FOOD, SHOPPING, OTHER), r.perCategory.map { it.category })
        assertEquals(0.4889, r.macroF1) // (0.8 + 0 + 0.6667) / 3
    }

    @Test
    fun `a category predicted but never expected has zero precision and recall`() {
        val r = CategorizationEvaluator.evaluate(listOf(outcome("x", OTHER, TRANSPORT)))
        val t = r.scoreOf(TRANSPORT)!!
        assertEquals(0, t.support); assertEquals(1, t.predicted); assertEquals(0.0, t.f1)
    }

    @Test
    fun `the subcategory is scored only where one is expected and the category is right`() {
        val r = CategorizationEvaluator.evaluate(sample)
        assertEquals(2, r.subcategoryScored); assertEquals(1, r.subcategoryCorrect); assertEquals(0.5, r.subcategoryAccuracy)
    }

    @Test
    fun `no expected subcategories gives a zero subcategory score`() {
        val r = CategorizationEvaluator.evaluate(listOf(outcome("x", FOOD, FOOD)))
        assertEquals(0, r.subcategoryScored); assertEquals(0.0, r.subcategoryAccuracy)
    }

    @Test
    fun `confusion pairs are counted, largest first, with their ids`() {
        val r = CategorizationEvaluator.evaluate(sample + outcome("f", SHOPPING, FOOD))
        assertEquals(Confusion(SHOPPING, FOOD, 2, listOf("c", "f")), r.confusions.first())
        assertEquals(Confusion(SHOPPING, OTHER, 1, listOf("d")), r.confusions[1])
    }

    @Test
    fun `misses list every wrong row in id order with what was expected and given`() {
        val r = CategorizationEvaluator.evaluate(sample)
        assertEquals(listOf("c", "d"), r.misses.map { it.id })
        val d = r.misses[1]
        assertEquals(Label(SHOPPING), d.expected); assertEquals(Label(OTHER), d.predicted); assertEquals("remembered", d.source)
        assertEquals("UPI-PAYEE d", d.merchantRaw)
    }

    @Test
    fun `rows are counted by the source of their label and accuracy is given per tag`() {
        val r = CategorizationEvaluator.evaluate(sample)
        assertEquals(mapOf("remembered" to 1, "rules" to 4), r.bySource)
        assertEquals(listOf(TagScore("card", 2, 1, 0.5), TagScore("gateway", 1, 0, 0.0)), r.byTag)
    }

    @Test
    fun `nothing to evaluate and duplicate ids are rejected`() {
        assertFailsWith<IllegalArgumentException> { CategorizationEvaluator.evaluate(emptyList()) }
        assertFailsWith<IllegalArgumentException> { CategorizationEvaluator.evaluate(listOf(outcome("a", FOOD, FOOD), outcome("a", FOOD, OTHER))) }
    }

    @Test
    fun `cases and labels validate themselves`() {
        assertFailsWith<IllegalArgumentException> { CaseInput(" ", null, null, TransactionType.DEBIT) }
        assertFailsWith<IllegalArgumentException> { CaseInput("X", null, null, TransactionType.REFUND) }
        assertFailsWith<IllegalArgumentException> { Label(FOOD, " ") }
        assertFailsWith<IllegalArgumentException> { LabelledCase(" ", CaseInput(null, "a@ybl", null, TransactionType.CREDIT), Label(FOOD)) }
        assertTrue(CaseInput(null, "a@ybl", null, TransactionType.CREDIT).merchantRaw == null)
    }
}
