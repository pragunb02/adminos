package dev.adminos.api.domain.agent

import dev.adminos.api.domain.common.workflow.RuleResult
import dev.adminos.api.domain.common.workflow.TransactionData
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
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
    private val users = dev.adminos.api.domain.identity.InMemoryUserRepository()
    private val tolerances = InMemoryAnomalyToleranceRepository()
    private val service = AnomalyDetectorService(anomalies, transactions, dev.adminos.api.domain.identity.UserCalendar(users), tolerances)

    /** A fixed daytime instant (12:00 IST): context windows are relative to the transaction, not the clock (AOS-505). */
    private val txnTime: Instant = Instant.parse("2026-03-10T06:30:00Z")

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

    // ── evaluate (AOS-505: windows relative to the transaction) ──

    private suspend fun spend(
        amount: String, merchant: String = "Swiggy", at: Instant = txnTime, currency: String = "INR",
        category: TransactionCategory = TransactionCategory.FOOD, nature: TransactionNature = TransactionNature.SPEND, save: Boolean = true
    ) = Transaction(
        userId = userId, type = TransactionType.DEBIT, nature = nature, amount = BigDecimal(amount), currency = currency,
        merchantName = merchant, merchantRaw = "UPI-$merchant", accountLast4 = "4521", paymentMethod = PaymentMethod.UPI,
        category = category, transactedAt = at
    ).let { if (save) transactions.save(it) else it }

    @Test
    fun `an ordinary spend creates no anomaly`() = runBlocking<Unit> {
        assertNull(service.evaluate(spend("349.00")))
        assertEquals(0L, anomalies.countByUserId(userId))
    }

    @Test
    fun `only spends are checked, never refunds, income or transfers`() = runBlocking<Unit> {
        listOf(TransactionNature.REFUND, TransactionNature.INCOME, TransactionNature.TRANSFER).forEach {
            assertNull(service.evaluate(spend("1.00", currency = "USD", nature = it)), "$it")
        }
    }

    @Test
    fun `with little history, over 3x the average of the 30 days before it, from 2,000 rupees, is a large amount`() = runBlocking<Unit> {
        listOf(400, 600, 500).forEachIndexed { i, a -> spend("$a.00", merchant = "Zomato", at = txnTime.minus(i + 1L, ChronoUnit.DAYS)) }
        spend("90000.00", merchant = "Old", at = txnTime.minus(31, ChronoUnit.DAYS)) // outside the 30-day fallback window
        spend("90000.00", merchant = "Later", at = txnTime.plus(1, ChronoUnit.DAYS)) // after it: not history
        spend("90000.00", merchant = "Trip", category = TransactionCategory.SHOPPING, at = txnTime.minus(1, ChronoUnit.DAYS)) // other category
        val big = spend("2400.00", merchant = "Barbeque Nation")

        val result = assertNotNull(service.evaluate(big))
        assertEquals(AnomalyType.LARGE_AMOUNT, result.type)
        assertEquals(0.7, result.confidenceScore)
        assertEquals(big.id, result.transactionId)
        assertEquals("₹2,400 is more than usual for Food: usually ₹450–₹550 (3 spends in 30 days)", result.reason)
        assertEquals(setOf("large_amount"), result.rulesFired)
        assertEquals("barbeque nation", result.merchantKey)
    }

    @Test
    fun `a merchant with its own history is judged by it, and the usual range is in the reason`() = runBlocking<Unit> {
        listOf(350, 420, 500, 610, 700, 480).forEachIndexed { i, a -> spend("$a.00", merchant = "Swiggy", at = txnTime.minus(i * 9L + 1, ChronoUnit.DAYS)) }
        val result = assertNotNull(service.evaluate(spend("4800.00", merchant = "Swiggy")))
        assertEquals("₹4,800 is more than usual at Swiggy: usually ₹435–₹582.50 (6 payments in 180 days)", result.reason)
    }

    @Test
    fun `a dismissed merchant's tolerance relaxes the amount rule, within its cap`() = runBlocking<Unit> {
        listOf(2000, 2050, 1950, 2020, 1980).forEachIndexed { i, a -> spend("$a.00", merchant = "Big Bazaar", at = txnTime.minus(i * 7L + 1, ChronoUnit.DAYS)) }
        // Median ₹2,000, spread floored at 10% (₹200): ₹3,000 is z = 5, over 3.5 at the default tolerance.
        assertNotNull(service.evaluate(spend("3000.00", merchant = "Big Bazaar")))
        repeat(5) { tolerances.record(userId, "big bazaar", TolerableRule.LARGE_AMOUNT, AlertFeedback.DISMISSED, txnTime) }
        // Capped at 3 steps (threshold 6.5): ₹3,010 (z ≈ 5 with ₹3,000 now in the history) is relaxed away…
        assertNull(service.evaluate(spend("3010.00", merchant = "Big Bazaar", at = txnTime.plusSeconds(60))))
        // …but a fraud far above normal still alerts.
        assertNotNull(service.evaluate(spend("26000.00", merchant = "Big Bazaar", at = txnTime.plusSeconds(120))))
    }

    @Test
    fun `under 2,000 rupees, or with fewer than 3 earlier spends, it isn't a large amount`() = runBlocking<Unit> {
        listOf(100, 120, 110).forEachIndexed { i, a -> spend("$a.00", merchant = "Chai", at = txnTime.minus(i + 1L, ChronoUnit.DAYS)) }
        assertNull(service.evaluate(spend("1500.00", merchant = "Dinner")), "over 3x, but small")
        val fresh = UUID.randomUUID()
        val firstEver = Transaction(userId = fresh, type = TransactionType.DEBIT, amount = BigDecimal("50000.00"), merchantName = "Laptop",
            category = TransactionCategory.SHOPPING, transactedAt = txnTime)
        transactions.save(firstEver)
        assertNull(service.evaluate(firstEver), "no history to compare with")
    }

    @Test
    fun `an imported statement row is compared with the days before it, not with today`() = runBlocking<Unit> {
        // March history; checking a March row long after: today's spending must not matter.
        listOf(400, 600, 500).forEachIndexed { i, a -> spend("$a.00", merchant = "Zomato", at = txnTime.minus(i + 1L, ChronoUnit.DAYS)) }
        spend("100000.00", merchant = "Recent", at = Instant.now().minus(1, ChronoUnit.DAYS))
        assertNotNull(service.evaluate(spend("2400.00")))
    }

    @Test
    fun `the same amount at the same merchant in the 24 hours up to it, even the same minute, is a duplicate`() = runBlocking<Unit> {
        spend("349.00", merchant = "SWIGGY", at = txnTime)
        val result = assertNotNull(service.evaluate(spend("349.00")))
        assertEquals(AnomalyType.DUPLICATE_CHARGE, result.type)
        assertEquals("Same amount ₹349.00 at Swiggy within 24 hours", result.reason)
    }

    @Test
    fun `a charge 25 hours earlier, or a different amount, isn't a duplicate`() = runBlocking<Unit> {
        spend("349.00", at = txnTime.minus(25, ChronoUnit.HOURS))
        spend("350.00", at = txnTime.minus(1, ChronoUnit.HOURS))
        assertNull(service.evaluate(spend("349.00")))
    }

    @Test
    fun `odd hours are judged in the user's own time zone`() = runBlocking<Unit> {
        // 21:00Z is 02:30 in India (odd) but 21:00 in London (not).
        users.save(dev.adminos.api.domain.identity.User(id = userId, email = "p@example.com", googleId = "g", timezone = "Europe/London"))
        assertNull(service.evaluate(spend("349.00", at = Instant.parse("2026-03-10T21:00:00Z"))))
        val other = UUID.randomUUID()
        val late = Transaction(userId = other, type = TransactionType.DEBIT, amount = BigDecimal("349.00"), merchantName = "Swiggy", transactedAt = Instant.parse("2026-03-10T21:00:00Z"))
        transactions.save(late)
        assertEquals(AnomalyType.ODD_HOURS, assertNotNull(service.evaluate(late)).type, "no user record: India time")
        users.save(dev.adminos.api.domain.identity.User(id = other, email = "q@example.com", googleId = "g2", timezone = "Not/AZone"))
        val again = late.copy(id = UUID.randomUUID(), amount = BigDecimal("410.00"))
        transactions.save(again)
        assertEquals(AnomalyType.ODD_HOURS, assertNotNull(service.evaluate(again)).type, "invalid zone: India time")
    }

    @Test
    fun `several firing rules combine confidence and pick the strongest rule as the type`() = runBlocking<Unit> {
        val result = assertNotNull(service.evaluate(spend("1.00", currency = "USD", merchant = "STRIPE*TEST")))
        assertEquals(AnomalyType.CARD_TESTING, result.type)
        assertEquals(0.95, result.confidenceScore, 1e-9)
        assertEquals("Foreign currency charge in USD; Amount of exactly ₹1.00 matches card testing pattern", result.reason)
    }

    @Test
    fun `a transaction is checked once, so a second check or a racing save adds nothing`() = runBlocking<Unit> {
        val t = spend("1.00")
        assertNotNull(service.evaluate(t))
        assertNull(service.evaluate(t))
        assertEquals(1L, anomalies.countByUserId(userId))
        val racing = AnomalyDetectorService(object : AnomalyRepository by anomalies {
            override suspend fun findByTransactionId(transactionId: UUID): Anomaly? = null // looked before the other instance saved
        }, transactions, dev.adminos.api.domain.identity.UserCalendar(users), tolerances)
        assertNull(racing.evaluate(t))
        assertEquals(1L, anomalies.countByUserId(userId))
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
            txnData(at = txnTime, id = "self")
        )
        assertNull(DuplicateChargeRule(recent).evaluate(txnData(id = "self")))
    }

    @Test
    fun `without ids, only earlier charges count as duplicates`() {
        assertNull(DuplicateChargeRule(listOf(txnData(id = null))).evaluate(txnData(id = null)))
        assertNotNull(DuplicateChargeRule(listOf(txnData(id = null, at = txnTime.minusSeconds(60)))).evaluate(txnData(id = null)))
    }

    @Test
    fun `a duplicate under 100 rupees is recorded below the alert bar, from 100 it alerts`() {
        val chai = DuplicateChargeRule(listOf(txnData(amount = "20.00", merchant = "Campus Chai", at = txnTime.minus(3, ChronoUnit.HOURS))))
        assertEquals(DuplicateChargeRule.SMALL_CONFIDENCE, assertNotNull(chai.evaluate(txnData(amount = "20.00", merchant = "Campus Chai"))).confidence)
        val meal = DuplicateChargeRule(listOf(txnData(amount = "100.00", at = txnTime.minus(3, ChronoUnit.HOURS))))
        assertEquals(0.75, assertNotNull(meal.evaluate(txnData(amount = "100.00"))).confidence)
        val asBefore = DuplicateChargeRule(listOf(txnData(amount = "20.00", at = txnTime.minus(3, ChronoUnit.HOURS))), smallAmount = BigDecimal.ZERO)
        assertEquals(0.75, assertNotNull(asBefore.evaluate(txnData(amount = "20.00"))).confidence)
    }

    @Test
    fun `duplicate rule with no recent transactions never fires`() {
        assertNull(DuplicateChargeRule().evaluate(txnData()))
    }
}
