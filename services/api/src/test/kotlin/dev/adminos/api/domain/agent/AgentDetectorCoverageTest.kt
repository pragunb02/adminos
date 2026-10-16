package dev.adminos.api.domain.agent

import dev.adminos.api.domain.common.workflow.RuleResult
import dev.adminos.api.domain.common.workflow.TransactionData
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionType
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AnomalyDetectorService end to end over the in-memory repositories, plus rule edge cases (AOS-312). */
class AgentDetectorCoverageTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val userId = UUID.randomUUID()
    private val anomalies = InMemoryAnomalyRepository()
    private val transactions = InMemoryTransactionRepository()
    private val service = AnomalyDetectorService(anomalies, transactions)

    /**
     * The repository windows (30 days / 24 hours) are relative to the real clock and cannot be
     * injected, so pick a recent daytime instant: the latest IST hour 0/6/12/18 not after now.
     * None of those hours is in the odd-hours window (01:00-04:59), so OddHoursRule stays quiet.
     */
    private val txnTime: Instant = ZonedDateTime.now(ist).truncatedTo(ChronoUnit.HOURS)
        .let { it.withHour(it.hour / 6 * 6) }.toInstant()

    private fun txnData(
        amount: String = "349.00", currency: String = "INR", merchant: String = "Swiggy",
        category: String = "food", at: Instant = txnTime, id: String? = UUID.randomUUID().toString()
    ) = TransactionData(
        userId = userId.toString(), amount = BigDecimal(amount), currency = currency, merchantName = merchant,
        category = category, transactedAt = at, transactionId = id
    )

    private suspend fun saveDebit(
        amount: String, merchant: String = "Swiggy", at: Instant = txnTime.minus(30, ChronoUnit.MINUTES),
        method: PaymentMethod? = PaymentMethod.UPI
    ) = transactions.save(Transaction(
        userId = userId, type = TransactionType.DEBIT, amount = BigDecimal(amount), merchantName = merchant,
        merchantRaw = "UPI-$merchant", accountLast4 = "4521", paymentMethod = method,
        category = TransactionCategory.FOOD, transactedAt = at
    ))

    // ── evaluate ──

    @Test
    fun `an ordinary transaction creates no anomaly`() = runBlocking<Unit> {
        val result = service.evaluate(txnData())

        assertNull(result)
        assertEquals(0L, anomalies.countByUserId(userId))
    }

    @Test
    fun `an amount over 3x the repository category average is saved as a large amount anomaly`() = runBlocking<Unit> {
        saveDebit("400.00", merchant = "Zomato", at = txnTime.minus(3, ChronoUnit.DAYS))
        saveDebit("600.00", merchant = "Zomato", at = txnTime.minus(2, ChronoUnit.DAYS))
        val txn = txnData(amount = "1600.00")

        val result = service.evaluate(txn)

        assertNotNull(result)
        assertEquals(AnomalyType.LARGE_AMOUNT, result.type)
        assertEquals(0.7, result.confidenceScore)
        assertEquals(UUID.fromString(txn.transactionId), result.transactionId)
        assertEquals(result, anomalies.findById(result.id))
    }

    @Test
    fun `the same amount at the same merchant earlier today is saved as a duplicate charge`() = runBlocking<Unit> {
        saveDebit("349.00", method = PaymentMethod.UPI)
        saveDebit("120.00", method = null)

        val result = service.evaluate(txnData(amount = "349.00"))

        assertNotNull(result)
        assertEquals(AnomalyType.DUPLICATE_CHARGE, result.type)
        assertEquals(0.75, result.confidenceScore)
        assertTrue(result.reason.contains("Swiggy"))
    }

    @Test
    fun `several firing rules combine confidence and pick the strongest rule as the type`() = runBlocking<Unit> {
        val result = service.evaluate(txnData(amount = "1.00", currency = "USD", merchant = "STRIPE*TEST"))

        assertNotNull(result)
        assertEquals(AnomalyType.CARD_TESTING, result.type)
        assertEquals(0.95, result.confidenceScore, 1e-9)
        assertEquals(
            "Foreign currency charge in USD; Amount of exactly ₹1.00 matches card testing pattern",
            result.reason
        )
    }

    @Test
    fun `a transaction without an id gets a generated transaction id`() = runBlocking<Unit> {
        val first = service.evaluate(txnData(currency = "USD", id = null))
        val second = service.evaluate(txnData(currency = "USD", id = null))

        assertNotNull(first); assertNotNull(second)
        assertNotEquals(first.transactionId, second.transactionId)
        assertEquals(AnomalyType.FOREIGN_CURRENCY, first.type)
    }

    // ── computeConfidence / determineType ──

    @Test
    fun `confidence of no fired rules is zero`() {
        assertEquals(0.0, AnomalyDetectorService.computeConfidence(emptyList()))
    }

    @Test
    fun `confidence is capped at one`() {
        val fired = List(5) { RuleResult("card_testing", 0.9, "r") }
        assertEquals(1.0, AnomalyDetectorService.computeConfidence(fired))
    }

    @Test
    fun `type of no fired rules defaults to large amount`() {
        assertEquals(AnomalyType.LARGE_AMOUNT, AnomalyDetectorService.determineType(emptyList()))
    }

    @Test
    fun `type follows the highest-confidence rule name`() {
        val cases = mapOf(
            "card_testing" to AnomalyType.CARD_TESTING,
            "foreign_currency" to AnomalyType.FOREIGN_CURRENCY,
            "odd_hours" to AnomalyType.ODD_HOURS,
            "duplicate_charge" to AnomalyType.DUPLICATE_CHARGE,
            "large_amount" to AnomalyType.LARGE_AMOUNT,
            "something_new" to AnomalyType.LARGE_AMOUNT
        )
        for ((rule, expected) in cases) {
            val fired = listOf(RuleResult("large_amount", 0.1, "low"), RuleResult(rule, 0.99, "high"))
            assertEquals(expected, AnomalyDetectorService.determineType(fired), rule)
        }
    }

    // ── Rule edge cases ──

    @Test
    fun `large amount rule ignores a zero category average`() {
        val rule = LargeAmountRule(mapOf("food" to BigDecimal.ZERO))
        assertNull(rule.evaluate(txnData(amount = "5000.00")))
    }

    @Test
    fun `large amount rule does not fire at exactly 3x the average`() {
        val rule = LargeAmountRule(mapOf("food" to BigDecimal("500")))
        assertNull(rule.evaluate(txnData(amount = "1500.00")))
    }

    @Test
    fun `large amount rule with default averages never fires`() {
        assertNull(LargeAmountRule().evaluate(txnData(amount = "999999.00")))
    }

    @Test
    fun `odd hours rule fires from 01-00 through 04-59 local time only`() {
        val rule = OddHoursRule()
        val day = ZonedDateTime.of(2026, 10, 12, 0, 0, 0, 0, ist)
        fun at(h: Int, m: Int) = txnData(at = day.withHour(h).withMinute(m).toInstant())

        assertNull(rule.evaluate(at(0, 59)))
        val fired = rule.evaluate(at(1, 5))
        assertNotNull(fired)
        assertEquals("Transaction at unusual hour: 1:05 local time", fired.reason)
        assertNotNull(rule.evaluate(at(4, 59)))
        assertNull(rule.evaluate(at(5, 0)))
    }

    @Test
    fun `odd hours rule uses the configured timezone`() {
        val rule = OddHoursRule(ZoneId.of("UTC"))
        // 02:00 UTC is 07:30 IST: odd in UTC, not in India.
        val result = rule.evaluate(txnData(at = Instant.parse("2026-10-12T02:00:00Z")))
        assertNotNull(result)
        assertNull(OddHoursRule().evaluate(txnData(at = Instant.parse("2026-10-12T02:00:00Z"))))
    }

    @Test
    fun `card testing rule fires for exactly two rupees in any scale`() {
        assertNotNull(CardTestingRule().evaluate(txnData(amount = "2.00")))
        assertNotNull(CardTestingRule().evaluate(txnData(amount = "1")))
        assertNull(CardTestingRule().evaluate(txnData(amount = "1.50")))
    }

    @Test
    fun `duplicate rule matches the merchant case-insensitively`() {
        val earlier = txnData(merchant = "SWIGGY", at = txnTime.minus(2, ChronoUnit.HOURS))
        assertNotNull(DuplicateChargeRule(listOf(earlier)).evaluate(txnData(merchant = "swiggy")))
    }

    @Test
    fun `duplicate rule ignores other merchants, other amounts, old and later transactions`() {
        val recent = listOf(
            txnData(merchant = "Zomato", at = txnTime.minus(1, ChronoUnit.HOURS)),
            txnData(amount = "350.00", at = txnTime.minus(1, ChronoUnit.HOURS)),
            txnData(at = txnTime.minus(25, ChronoUnit.HOURS)),
            txnData(at = txnTime.plus(1, ChronoUnit.HOURS)),
            txnData(at = txnTime)
        )
        assertNull(DuplicateChargeRule(recent).evaluate(txnData()))
    }

    @Test
    fun `duplicate rule with no recent transactions never fires`() {
        assertNull(DuplicateChargeRule().evaluate(txnData()))
    }
}
