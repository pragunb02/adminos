package dev.adminos.api.domain.agent.baselines

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-713: the two baseline models, the tolerance level and the order statistics behind them. */
class BaselineModelsTest {
    private fun amounts(vararg v: Int) = v.map { BigDecimal(it) }
    private fun rupees(v: String) = BigDecimal(v)
    /** The linear-scale model: the textbook numbers are easy to check by hand. */
    private val robust = MedianMadBaseline(scale = AmountScale.LINEAR)
    private val logScale = MedianMadBaseline()
    private val legacy = MeanMultipleBaseline()

    // ── MedianMadBaseline ──

    @Test
    fun `fewer than five earlier amounts is too little history`() {
        assertNull(robust.fit(amounts(300, 400, 500, 600)))
        assertNotNull(robust.fit(amounts(300, 400, 500, 600, 700)))
    }

    @Test
    fun `a spend far above the median by the modified z-score is unusual`() {
        // median 500, MAD 100: z = 0.6745 × (x − 500) / 100, over 3.5 from ₹1,018.92
        val b = assertNotNull(robust.fit(amounts(300, 400, 500, 600, 700)))
        assertEquals(0.6745 * 600 / 100, b.distance(rupees("1100")), 1e-9)
        assertTrue(b.isUnusual(rupees("1100"), ToleranceLevel.DEFAULT))
        assertFalse(b.isUnusual(rupees("1000"), ToleranceLevel.DEFAULT))
    }

    @Test
    fun `one outlier in the history doesn't drag the baseline up`() {
        // A ₹50,000 one-off would make the mean ₹8,750 and hide the next big spend; the median stays ₹500.
        val b = assertNotNull(robust.fit(amounts(400, 450, 500, 550, 600, 50000)))
        assertTrue(b.isUnusual(rupees("4800"), ToleranceLevel.DEFAULT))
        val mean = assertNotNull(legacy.fit(amounts(400, 450, 500, 550, 600, 50000)))
        assertFalse(mean.isUnusual(rupees("4800"), ToleranceLevel.DEFAULT))
    }

    @Test
    fun `an amount at or below the median is never unusual`() {
        val b = assertNotNull(robust.fit(amounts(300, 400, 500, 600, 700)))
        assertFalse(b.isUnusual(rupees("500"), ToleranceLevel.DEFAULT))
        assertFalse(b.isUnusual(rupees("10"), ToleranceLevel.DEFAULT))
        assertTrue(b.distance(rupees("10")) < 0)
    }

    @Test
    fun `each tolerance step raises the threshold by one z`() {
        val b = assertNotNull(robust.fit(amounts(300, 400, 500, 600, 700)))
        // z(1,300) = 5.396: unusual up to one step (4.5), not at two (5.5).
        assertTrue(b.isUnusual(rupees("1300"), ToleranceLevel(1)))
        assertFalse(b.isUnusual(rupees("1300"), ToleranceLevel(2)))
    }

    @Test
    fun `zero MAD falls back to the mean absolute deviation`() {
        // Four of six identical: MAD 0; mean absolute deviation 100 → spread 125.33.
        val b = assertNotNull(robust.fit(amounts(1000, 1000, 1000, 1000, 1300, 1300)))
        assertEquals(500.0 / (1.253314 * 100), b.distance(rupees("1500")), 1e-9)
    }

    @Test
    fun `identical history uses the minimum spread, so a few rupees more isn't an alert`() {
        val b = assertNotNull(robust.fit(amounts(649, 649, 649, 649, 649)))
        // Spread floor 10% of ₹649 = ₹64.90: ₹799 is z 2.3, ₹1,000 is z 5.4.
        assertFalse(b.isUnusual(rupees("799"), ToleranceLevel.DEFAULT))
        assertTrue(b.isUnusual(rupees("1000"), ToleranceLevel.DEFAULT))
        assertEquals(UsualRange(rupees("649.00"), rupees("649.00")), b.usualRange)
        assertEquals("₹649", b.usualRange.text())
    }

    @Test
    fun `a history of zero amounts never divides by zero`() {
        val b = assertNotNull(robust.fit(amounts(0, 0, 0, 0, 0)))
        assertEquals(Double.POSITIVE_INFINITY, b.distance(rupees("1")))
        assertEquals(0.0, b.distance(rupees("0")))
        assertTrue(b.isUnusual(rupees("1"), ToleranceLevel.DEFAULT))
    }

    // ── The log scale (the default) ──

    @Test
    fun `on the log scale a big order that is a usual multiple isn't unusual, a far bigger one is`() {
        // An online shop: ₹400 to ₹3,600. ₹6,000 is z 5.4 on rupees but z 2.6 on log rupees.
        val history = amounts(400, 800, 1200, 1800, 3600)
        assertTrue(assertNotNull(robust.fit(history)).isUnusual(rupees("6000"), ToleranceLevel.DEFAULT))
        val b = assertNotNull(logScale.fit(history))
        assertFalse(b.isUnusual(rupees("6000"), ToleranceLevel.DEFAULT))
        assertTrue(b.isUnusual(rupees("30000"), ToleranceLevel.DEFAULT))
    }

    @Test
    fun `on the log scale identical history allows 10 percent either way`() {
        val b = assertNotNull(logScale.fit(amounts(649, 649, 649, 649, 649)))
        assertEquals(kotlin.math.ln(799.0 / 649) / kotlin.math.ln(1.1), b.distance(rupees("799")), 1e-9)
        assertFalse(b.isUnusual(rupees("799"), ToleranceLevel.DEFAULT))
        assertTrue(b.isUnusual(rupees("1000"), ToleranceLevel.DEFAULT))
    }

    @Test
    fun `the log scale needs positive amounts`() {
        assertNull(logScale.fit(amounts(0, 400, 500, 600, 700)), "ln(0) is undefined: no baseline rather than a wrong one")
        val b = assertNotNull(logScale.fit(amounts(400, 450, 500, 550, 600)))
        assertEquals(Double.NEGATIVE_INFINITY, b.distance(rupees("0")))
        assertFalse(b.isUnusual(rupees("0"), ToleranceLevel.DEFAULT))
    }

    @Test
    fun `the usual range is the 25th to 75th percentile`() {
        val b = assertNotNull(robust.fit(amounts(350, 420, 480, 500, 610, 700)))
        assertEquals(UsualRange(rupees("435.00"), rupees("582.50")), b.usualRange)
        assertEquals("₹435–₹582.50", b.usualRange.text())
        assertEquals(6, b.sampleSize)
    }

    @Test
    fun `a median-MAD model refuses settings that make no sense`() {
        assertFailsWith<IllegalArgumentException> { MedianMadBaseline(minHistory = 2) }
        assertFailsWith<IllegalArgumentException> { MedianMadBaseline(threshold = 0.0) }
        assertFailsWith<IllegalArgumentException> { MedianMadBaseline(toleranceStep = -1.0) }
        assertFailsWith<IllegalArgumentException> { MedianMadBaseline(minRelativeSpread = 0.0) }
        assertEquals("median_mad_linear", robust.name)
        assertEquals("median_mad_log", logScale.name)
    }

    // ── MeanMultipleBaseline (today's rule) ──

    @Test
    fun `today's rule needs three amounts and fires above three times their mean`() {
        assertNull(legacy.fit(amounts(400, 600)))
        val b = assertNotNull(legacy.fit(amounts(400, 600, 500)))
        assertFalse(b.isUnusual(rupees("1500"), ToleranceLevel.DEFAULT), "exactly 3x is not over")
        assertTrue(b.isUnusual(rupees("1500.01"), ToleranceLevel.DEFAULT))
        assertEquals(3.0, b.distance(rupees("1500")), 1e-9)
        assertEquals("mean_multiple", legacy.name)
    }

    @Test
    fun `each tolerance step adds one to the multiple`() {
        val b = assertNotNull(legacy.fit(amounts(400, 600, 500)))
        assertFalse(b.isUnusual(rupees("2000"), ToleranceLevel(1)))
        assertTrue(b.isUnusual(rupees("2000.01"), ToleranceLevel(1)))
    }

    @Test
    fun `a zero mean never fires`() {
        val b = assertNotNull(legacy.fit(amounts(0, 0, 0)))
        assertFalse(b.isUnusual(rupees("5000"), ToleranceLevel.DEFAULT))
        assertEquals(0.0, b.distance(rupees("5000")))
    }

    @Test
    fun `a mean-multiple model refuses settings that make no sense`() {
        assertFailsWith<IllegalArgumentException> { MeanMultipleBaseline(minHistory = 0) }
        assertFailsWith<IllegalArgumentException> { MeanMultipleBaseline(multiple = 0) }
    }

    // ── ToleranceLevel, UsualRange, AmountStats ──

    @Test
    fun `a tolerance level stays within zero to three steps`() {
        assertEquals(ToleranceLevel(3), ToleranceLevel(2).moved(5))
        assertEquals(ToleranceLevel(0), ToleranceLevel(1).moved(-4))
        assertEquals(ToleranceLevel(2), ToleranceLevel(1).moved(1))
        assertFailsWith<IllegalArgumentException> { ToleranceLevel(4) }
        assertFailsWith<IllegalArgumentException> { ToleranceLevel(-1) }
    }

    @Test
    fun `a usual range runs low to high and reads in Indian rupees`() {
        assertFailsWith<IllegalArgumentException> { UsualRange(rupees("2"), rupees("1")) }
        assertEquals("₹1,20,000–₹1,50,000.50", UsualRange(rupees("120000"), rupees("150000.50")).text())
    }

    @Test
    fun `quantiles interpolate and validate their input`() {
        assertEquals(rupees("7.00"), AmountStats.quantile(amounts(7), 0.9))
        assertEquals(rupees("2.50"), AmountStats.median(amounts(4, 1, 3, 2)))
        assertEquals(rupees("1.00"), AmountStats.quantile(amounts(1, 2, 3), 0.0))
        assertEquals(rupees("3.00"), AmountStats.quantile(amounts(1, 2, 3), 1.0))
        assertFailsWith<IllegalArgumentException> { AmountStats.quantile(emptyList(), 0.5) }
        assertFailsWith<IllegalArgumentException> { AmountStats.quantile(amounts(1), 1.5) }
        assertFailsWith<IllegalArgumentException> { AmountStats.mean(emptyList()) }
    }
}
