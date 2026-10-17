package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionCategory.FOOD
import dev.adminos.api.domain.financial.TransactionCategory.OTHER
import dev.adminos.api.domain.financial.TransactionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** AOS-511: the no-regression gate and the report table. */
class RegressionGateTest {

    private fun outcome(id: String, expected: TransactionCategory, predicted: TransactionCategory, sub: String? = null) = Outcome(
        LabelledCase(id, CaseInput("UPI-PAYEE $id", null, PaymentMethod.UPI, TransactionType.DEBIT), Label(expected, sub), setOf("p2p")),
        Prediction(Label(predicted, sub), "rules")
    )

    private val good = CategorizationEvaluator.evaluate(listOf(outcome("a", FOOD, FOOD, "cafe"), outcome("b", OTHER, OTHER), outcome("c", FOOD, FOOD)))
    private val worse = CategorizationEvaluator.evaluate(listOf(outcome("a", FOOD, FOOD, "cafe"), outcome("b", OTHER, OTHER), outcome("c", FOOD, OTHER)))

    @Test
    fun `the same scores as the baseline pass`() {
        assertEquals(emptyList(), RegressionGate.regressions(good, Baseline.of("rules", good)))
        assertEquals(emptyList(), RegressionGate.improvements(good, Baseline.of("rules", good)))
    }

    @Test
    fun `a drop names overall accuracy and every category F1 that fell`() {
        val drops = RegressionGate.regressions(worse, Baseline.of("rules", good)).map { it.metric }
        assertEquals(listOf("overall accuracy", "FOOD F1", "OTHER F1"), drops)
        assertEquals("overall accuracy dropped from 1.0 to 0.6667", RegressionGate.regressions(worse, Baseline.of("rules", good)).first().toString())
    }

    @Test
    fun `a subcategory drop is a regression`() {
        val baseline = Baseline.of("rules", good).copy(subcategoryAccuracy = 1.0)
        val noSub = good.copy(subcategoryAccuracy = 0.5)
        assertEquals(listOf("subcategory accuracy"), RegressionGate.regressions(noSub, baseline).map { it.metric })
    }

    @Test
    fun `a category in the baseline that is now missing counts as F1 zero`() {
        val baseline = Baseline.of("rules", good).copy(f1 = mapOf("TRANSPORT" to 0.5))
        assertEquals(listOf(Regression("TRANSPORT F1", 0.5, 0.0)), RegressionGate.regressions(good, baseline))
    }

    @Test
    fun `improvements are listed so the baseline can be raised deliberately`() {
        val ups = RegressionGate.improvements(good, Baseline.of("rules", worse).copy(subcategoryAccuracy = 0.0))
        assertEquals(4, ups.size)
        assertTrue(ups.first().startsWith("overall accuracy 0.6667 → 1.0"))
    }

    @Test
    fun `a baseline records the expected categories and validates itself`() {
        val b = Baseline.of("rules", good)
        assertEquals(3, b.goldenRows); assertEquals(setOf("FOOD", "OTHER"), b.f1.keys)
        assertFailsWith<IllegalArgumentException> { b.copy(subject = " ") }
        assertFailsWith<IllegalArgumentException> { b.copy(goldenRows = 0) }
        assertFailsWith<IllegalArgumentException> { b.copy(f1 = mapOf("RENT" to 1.0)) }
    }

    @Test
    fun `the table shows the headline, every category, confusions, tags and misses`() {
        val text = ReportFormatter.table("demo", worse)
        assertTrue(text.contains("== demo =="))
        assertTrue(text.contains("accuracy 66.7%"))
        assertTrue(text.contains("FOOD → OTHER: 1  [c]"))
        assertTrue(text.contains("p2p"))
        assertTrue(text.contains("expected FOOD"))
    }

    @Test
    fun `the table cuts long miss lists and points to the JSON`() {
        val many = CategorizationEvaluator.evaluate((1..3).map { outcome("m$it", FOOD, OTHER) })
        val text = ReportFormatter.table("demo", many, maxMisses = 2)
        assertTrue(text.contains("… 1 more in the JSON report"))
    }

    @Test
    fun `a perfect report has no confusion or miss section`() {
        val perfect = CategorizationEvaluator.evaluate(listOf(Outcome(
            LabelledCase("a", CaseInput(null, "x@ybl", null, TransactionType.DEBIT), Label(OTHER)), Prediction(Label(OTHER), "rules"))))
        val text = ReportFormatter.table("demo", perfect)
        assertTrue(!text.contains("confusions") && !text.contains("misses") && !text.contains("by tag"))
    }
}
