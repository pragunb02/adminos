package dev.adminos.api.domain.agent

import dev.adminos.api.domain.common.workflow.RuleResult
import dev.adminos.api.domain.common.workflow.TransactionData
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnomalyDetectorServiceTest {

    // ── Rule 1: Large Amount ──

    @Test
    fun `LargeAmountRule fires when amount exceeds 3x category average`() {
        val averages = mapOf("food" to BigDecimal(500))
        val rule = LargeAmountRule(averages)
        val txn = testTransaction(amount = BigDecimal(1600), category = "food")

        val result = rule.evaluate(txn)
        assertNotNull(result)
        assertEquals("large_amount", result.ruleName)
        assertEquals(0.7, result.confidence)
    }

    @Test
    fun `LargeAmountRule does not fire when amount is below threshold`() {
        val averages = mapOf("food" to BigDecimal(500))
        val rule = LargeAmountRule(averages)
        val txn = testTransaction(amount = BigDecimal(1400), category = "food")

        val result = rule.evaluate(txn)
        assertNull(result)
    }

    @Test
    fun `LargeAmountRule does not fire when no category average exists`() {
        val rule = LargeAmountRule(emptyMap())
        val txn = testTransaction(amount = BigDecimal(10000), category = "food")

        val result = rule.evaluate(txn)
        assertNull(result)
    }

    // ── Rule 2: Foreign Currency ──

    @Test
    fun `ForeignCurrencyRule fires for non-INR currency`() {
        val rule = ForeignCurrencyRule()
        val txn = testTransaction(currency = "USD")

        val result = rule.evaluate(txn)
        assertNotNull(result)
        assertEquals("foreign_currency", result.ruleName)
        assertEquals(0.8, result.confidence)
    }

    @Test
    fun `ForeignCurrencyRule does not fire for INR`() {
        val rule = ForeignCurrencyRule()
        val txn = testTransaction(currency = "INR")

        assertNull(rule.evaluate(txn))
    }

    // ── Rule 3: Odd Hours ──

    @Test
    fun `OddHoursRule fires for transactions between 1am and 5am`() {
        val tz = ZoneId.of("Asia/Kolkata")
        val rule = OddHoursRule(tz)
        // 3:00 AM IST
        val at3am = ZonedDateTime.of(2025, 1, 15, 3, 0, 0, 0, tz).toInstant()
        val txn = testTransaction(transactedAt = at3am)

        val result = rule.evaluate(txn)
        assertNotNull(result)
        assertEquals("odd_hours", result.ruleName)
        assertEquals(0.6, result.confidence)
    }

    @Test
    fun `OddHoursRule does not fire for daytime transactions`() {
        val tz = ZoneId.of("Asia/Kolkata")
        val rule = OddHoursRule(tz)
        val at10am = ZonedDateTime.of(2025, 1, 15, 10, 0, 0, 0, tz).toInstant()
        val txn = testTransaction(transactedAt = at10am)

        assertNull(rule.evaluate(txn))
    }

    @Test
    fun `OddHoursRule does not fire at exactly 5am`() {
        val tz = ZoneId.of("Asia/Kolkata")
        val rule = OddHoursRule(tz)
        val at5am = ZonedDateTime.of(2025, 1, 15, 5, 0, 0, 0, tz).toInstant()
        val txn = testTransaction(transactedAt = at5am)

        assertNull(rule.evaluate(txn))
    }

    // ── Rule 4: Card Testing ──

    @Test
    fun `CardTestingRule fires for amount 1_00`() {
        val rule = CardTestingRule()
        val txn = testTransaction(amount = BigDecimal("1.00"))

        val result = rule.evaluate(txn)
        assertNotNull(result)
        assertEquals("card_testing", result.ruleName)
        assertEquals(0.9, result.confidence)
    }

    @Test
    fun `CardTestingRule fires for amount 2_00`() {
        val rule = CardTestingRule()
        val txn = testTransaction(amount = BigDecimal("2.00"))

        assertNotNull(rule.evaluate(txn))
    }

    @Test
    fun `CardTestingRule does not fire for other amounts`() {
        val rule = CardTestingRule()
        val txn = testTransaction(amount = BigDecimal("3.00"))

        assertNull(rule.evaluate(txn))
    }

    // ── Rule 5: Duplicate Charge ──

    @Test
    fun `DuplicateChargeRule fires for same amount and merchant within 24h`() {
        val now = Instant.now()
        val earlier = now.minusSeconds(3600) // 1 hour ago
        val recentTxn = testTransaction(
            amount = BigDecimal(500),
            merchantName = "Zomato",
            transactedAt = earlier
        )
        val rule = DuplicateChargeRule(listOf(recentTxn))
        val txn = testTransaction(
            amount = BigDecimal(500),
            merchantName = "Zomato",
            transactedAt = now
        )

        val result = rule.evaluate(txn)
        assertNotNull(result)
        assertEquals("duplicate_charge", result.ruleName)
        assertEquals(0.75, result.confidence)
    }

    @Test
    fun `DuplicateChargeRule does not fire for different amounts`() {
        val now = Instant.now()
        val earlier = now.minusSeconds(3600)
        val recentTxn = testTransaction(
            amount = BigDecimal(500),
            merchantName = "Zomato",
            transactedAt = earlier
        )
        val rule = DuplicateChargeRule(listOf(recentTxn))
        val txn = testTransaction(
            amount = BigDecimal(600),
            merchantName = "Zomato",
            transactedAt = now
        )

        assertNull(rule.evaluate(txn))
    }

    @Test
    fun `DuplicateChargeRule does not fire beyond 24 hours`() {
        val now = Instant.now()
        val longAgo = now.minusSeconds(25 * 3600) // 25 hours ago
        val recentTxn = testTransaction(
            amount = BigDecimal(500),
            merchantName = "Zomato",
            transactedAt = longAgo
        )
        val rule = DuplicateChargeRule(listOf(recentTxn))
        val txn = testTransaction(
            amount = BigDecimal(500),
            merchantName = "Zomato",
            transactedAt = now
        )

        assertNull(rule.evaluate(txn))
    }

    // ── Confidence Aggregation ──

    @Test
    fun `single rule confidence equals rule confidence`() {
        val fired = listOf(RuleResult("test", 0.7, "reason"))
        assertEquals(0.7, AnomalyDetectorService.computeConfidence(fired))
    }

    @Test
    fun `two rules add 0_05 bonus to max`() {
        val fired = listOf(
            RuleResult("r1", 0.7, "reason1"),
            RuleResult("r2", 0.8, "reason2")
        )
        val confidence = AnomalyDetectorService.computeConfidence(fired)
        assertTrue(confidence >= 0.849 && confidence <= 0.851, "Expected ~0.85, got $confidence")
    }

    @Test
    fun `three rules add 0_10 bonus to max`() {
        val fired = listOf(
            RuleResult("r1", 0.6, "reason1"),
            RuleResult("r2", 0.7, "reason2"),
            RuleResult("r3", 0.8, "reason3")
        )
        val confidence = AnomalyDetectorService.computeConfidence(fired)
        assertTrue(confidence >= 0.899 && confidence <= 0.901, "Expected ~0.90, got $confidence")
    }

    @Test
    fun `confidence is capped at 1_0`() {
        val fired = listOf(
            RuleResult("r1", 0.9, "reason1"),
            RuleResult("r2", 0.9, "reason2"),
            RuleResult("r3", 0.9, "reason3"),
            RuleResult("r4", 0.9, "reason4"),
            RuleResult("r5", 0.9, "reason5")
        )
        val confidence = AnomalyDetectorService.computeConfidence(fired)
        assertTrue(confidence <= 1.0, "Confidence $confidence should be <= 1.0")
        assertEquals(1.0, confidence)
    }

    // ── Helpers ──

    private fun testTransaction(
        amount: BigDecimal = BigDecimal(500),
        currency: String = "INR",
        merchantName: String = "TestMerchant",
        category: String = "other",
        transactedAt: Instant = Instant.now()
    ) = TransactionData(
        userId = "test-user",
        amount = amount,
        currency = currency,
        merchantName = merchantName,
        category = category,
        transactedAt = transactedAt
    )
}
