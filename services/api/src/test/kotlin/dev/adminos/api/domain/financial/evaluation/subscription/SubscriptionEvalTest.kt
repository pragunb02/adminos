package dev.adminos.api.domain.financial.evaluation.subscription

import dev.adminos.api.domain.financial.BillingCycle
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AOS-516: the shipped subscription detector may not score below the checked-in baseline on the
 * synthetic eval set (subscriptions vs regular shopping). Raising the floor is deliberate:
 * `./gradlew -q subscriptionEvalReport -PwriteBaseline`, then commit baseline.json with the change.
 */
class SubscriptionEvalTest {
    private val cases = SubscriptionEval.load()

    @Test
    fun `the detector scores no lower than the baseline on the eval set`() {
        val report = SubscriptionEval.report()
        val floor = SubscriptionEval.baseline()
        assertEquals(floor.cases, report.cases, "The eval set changed size: re-record the baseline with -PwriteBaseline")
        val drops = listOfNotNull(
            ("precision" to floor.precision).takeIf { report.precision < it.second - 1e-9 },
            ("recall" to floor.recall).takeIf { report.recall < it.second - 1e-9 },
            ("f1" to floor.f1).takeIf { report.f1 < it.second - 1e-9 },
            ("cycle accuracy" to floor.cycleAccuracy).takeIf { report.cycleAccuracy < it.second - 1e-9 }
        )
        if (drops.isNotEmpty()) {
            fail("Subscription detection got worse: ${drops.joinToString { "${it.first} below ${it.second}" }}\nMisses:\n" +
                report.misses.joinToString("\n") { "  - $it" })
        }
    }

    @Test
    fun `the eval set is synthetic, balanced and covers the hard cases`() {
        val header = SubscriptionEval::class.java.getResource(SubscriptionEval.RESOURCE)!!.readText().lineSequence().first()
        assertTrue(header.contains("SYNTHETIC"))
        assertTrue(cases.size >= 40, "${cases.size} cases")
        assertTrue(cases.count { it.expectedSubscription } >= 20)
        assertTrue(cases.count { !it.expectedSubscription } >= 20)
        val tags = cases.flatMap { it.tags }.toSet()
        for (t in listOf("plan-switch", "quick-commerce", "groceries", "food-delivery", "mandate", "price-rise", "unknown-merchant", "same-amount")) {
            assertTrue(t in tags, "no '$t' case")
        }
        assertEquals(BillingCycle.entries.toSet() - BillingCycle.DAILY, cases.mapNotNull { it.expectedCycle }.toSet())
    }

    @Test
    fun `scores count true and false positives and negatives`() {
        val (sub, not) = cases.first { it.expectedSubscription } to cases.first { !it.expectedSubscription }
        val r = SubscriptionEval.evaluate(listOf(
            SubscriptionOutcome(sub, sub.expectedCycle), SubscriptionOutcome(sub, BillingCycle.DAILY),
            SubscriptionOutcome(not, BillingCycle.WEEKLY), SubscriptionOutcome(sub, null), SubscriptionOutcome(not, null)
        ))
        assertEquals(listOf(2, 1, 1, 1), listOf(r.truePositives, r.falsePositives, r.falseNegatives, r.trueNegatives))
        assertEquals(0.6667, r.precision)
        assertEquals(0.6667, r.recall)
        assertEquals(0.6667, r.f1)
        assertEquals(0.5, r.cycleAccuracy)
        assertEquals(3, r.misses.size)
        val none = SubscriptionEval.evaluate(listOf(SubscriptionOutcome(not, null)))
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), listOf(none.precision, none.recall, none.f1, none.cycleAccuracy))
    }

    @Test
    fun `malformed rows fail with their line number`() {
        val header = "id,merchant_raw,upi_vpa,payment_method,charges,expected,expected_cycle,tags"
        assertTrue(assertFailsWith<IllegalArgumentException> { SubscriptionEval.parse("$header\nA1,X,,UPI,10:99,maybe,,") }.message!!.contains("line 2 (A1)"))
        assertFailsWith<IllegalArgumentException> { SubscriptionEval.parse("$header\nA1,X,,UPI,10:99:x,sub,MONTHLY,") }
        assertFailsWith<IllegalArgumentException> { SubscriptionEval.parse("$header\nA1,X,,UPI,10:99,sub,,") }
        assertFailsWith<IllegalArgumentException> { SubscriptionEval.parse("$header\nA1,X,,UPI,10:99,not,,\nA1,X,,UPI,10:99,not,,") }
        assertFailsWith<IllegalArgumentException> { SubscriptionEval.parse("$header\nA1,X,,UPI,10:99,not") }
        assertFailsWith<IllegalArgumentException> { SubscriptionEval.parse("id,raw\nA1,X") }
        assertFailsWith<IllegalStateException> { SubscriptionEval.load("/subscriptions/missing.csv") }
        assertFailsWith<IllegalStateException> { SubscriptionEval.baseline("/subscriptions/missing.json") }
        val c = SubscriptionEval.parse("# synthetic\n\n$header\nA1,X,x@ybl,,10:99:m 40:99,not,,a b\n").single()
        assertEquals(listOf(EvalCharge(10, BigDecimal("99"), true), EvalCharge(40, BigDecimal("99"), false)), c.charges)
        assertEquals(null, c.paymentMethod)
        assertEquals(setOf("a", "b"), c.tags)
    }
}
