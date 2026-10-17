package dev.adminos.api.domain.agent

import dev.adminos.api.domain.agent.baselines.ToleranceLevel
import dev.adminos.api.domain.audit.AuditActions
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-713: answering an alert, and what the answer teaches. */
class AnomalyReviewServiceTest {
    private val clock = TestClock(Instant.parse("2026-10-17T06:30:00Z"))
    private val userId = UUID.randomUUID()
    private val anomalies = InMemoryAnomalyRepository()
    private val transactions = InMemoryTransactionRepository()
    private val tolerances = InMemoryAnomalyToleranceRepository()
    private val audits = InMemoryAuditRepository()
    private val service = AnomalyReviewService(anomalies, transactions, tolerances, AuditService(audits, CoroutineScope(Dispatchers.Unconfined)), clock)

    private suspend fun anomaly(
        type: AnomalyType = AnomalyType.LARGE_AMOUNT, rules: Set<String> = setOf("large_amount"), merchantKey: String? = "swiggy",
        status: AnomalyStatus = AnomalyStatus.OPEN, owner: UUID = userId, transactionId: UUID = UUID.randomUUID()
    ) = anomalies.save(Anomaly(userId = owner, transactionId = transactionId, type = type, confidenceScore = 0.7, reason = "r",
        rulesFired = rules, merchantKey = merchantKey, status = status))

    private suspend fun level(merchant: String = "swiggy") = tolerances.find(userId, merchant, TolerableRule.LARGE_AMOUNT)?.level?.steps

    private suspend fun resolve(a: Anomaly, status: AnomalyStatus) = assertIs<AnomalyResolution.Resolved>(service.resolve(userId, a.id, status))

    @Test
    fun `this is normal raises the merchant's tolerance one step, by either button`() = runBlocking<Unit> {
        val r = resolve(anomaly(), AnomalyStatus.CONFIRMED_SAFE)
        assertEquals(1, level())
        assertEquals(AnomalyStatus.CONFIRMED_SAFE, r.anomaly.status)
        assertEquals(clock.now, r.anomaly.resolvedAt)
        assertEquals(AnomalyResolver.USER, r.anomaly.resolvedBy)
        assertEquals(listOf(1), r.tolerances.map { it.level.steps })
        resolve(anomaly(), AnomalyStatus.DISMISSED)
        assertEquals(2, level())
        assertEquals(2, tolerances.find(userId, "swiggy", TolerableRule.LARGE_AMOUNT)?.dismissals)
    }

    @Test
    fun `the tolerance is capped, so many dismissals can't silence a merchant`() = runBlocking<Unit> {
        repeat(6) { resolve(anomaly(), AnomalyStatus.DISMISSED) }
        assertEquals(ToleranceLevel.MAX, level())
    }

    @Test
    fun `this was unexpected lowers the tolerance, never below the start`() = runBlocking<Unit> {
        resolve(anomaly(), AnomalyStatus.CONFIRMED_FRAUD)
        assertEquals(0, level(), "already at the start: it stays")
        resolve(anomaly(), AnomalyStatus.DISMISSED)
        resolve(anomaly(), AnomalyStatus.DISMISSED)
        resolve(anomaly(), AnomalyStatus.CONFIRMED_FRAUD)
        assertEquals(1, level())
        assertEquals(2, tolerances.find(userId, "swiggy", TolerableRule.LARGE_AMOUNT)?.confirmations)
    }

    @Test
    fun `only the first answer teaches, so changing it later can't count twice`() = runBlocking<Unit> {
        val a = anomaly()
        resolve(a, AnomalyStatus.DISMISSED)
        val again = resolve(a, AnomalyStatus.CONFIRMED_FRAUD)
        assertEquals(1, level())
        assertEquals(emptyList(), again.tolerances)
        assertEquals(AnomalyStatus.CONFIRMED_FRAUD, again.anomaly.status)
    }

    @Test
    fun `crisp rules are never relaxed`() = runBlocking<Unit> {
        val r = resolve(anomaly(type = AnomalyType.CARD_TESTING, rules = setOf("card_testing", "duplicate_charge")), AnomalyStatus.DISMISSED)
        assertEquals(emptyList(), r.tolerances)
        assertNull(level())
    }

    @Test
    fun `an amount rule that fired beside a crisp one is still relaxed`() = runBlocking<Unit> {
        resolve(anomaly(type = AnomalyType.DUPLICATE_CHARGE, rules = setOf("duplicate_charge", "large_amount")), AnomalyStatus.DISMISSED)
        assertEquals(1, level())
    }

    @Test
    fun `a row from before V33 learns from its type and its transaction's merchant`() = runBlocking<Unit> {
        val t = transactions.save(Transaction(userId = userId, type = TransactionType.DEBIT, amount = BigDecimal("4800.00"),
            merchantName = "  Barbeque Nation ", category = TransactionCategory.FOOD, transactedAt = clock.now.minus(1, ChronoUnit.DAYS)))
        resolve(anomaly(rules = emptySet(), merchantKey = null, transactionId = t.id), AnomalyStatus.DISMISSED)
        assertEquals(1, level("barbeque nation"))
        // An old non-amount row teaches nothing.
        assertEquals(emptyList(), resolve(anomaly(type = AnomalyType.ODD_HOURS, rules = emptySet(), merchantKey = null), AnomalyStatus.DISMISSED).tolerances)
    }

    @Test
    fun `without a merchant there is nothing to relax`() = runBlocking<Unit> {
        val r = resolve(anomaly(merchantKey = null), AnomalyStatus.DISMISSED)
        assertEquals(emptyList(), r.tolerances)
        assertEquals(AnomalyStatus.DISMISSED, r.anomaly.status)
    }

    @Test
    fun `someone else's or a missing anomaly is not found, and changes nothing`() = runBlocking<Unit> {
        val theirs = anomaly(owner = UUID.randomUUID())
        assertEquals(AnomalyResolution.NotFound, service.resolve(userId, theirs.id, AnomalyStatus.DISMISSED))
        assertEquals(AnomalyResolution.NotFound, service.resolve(userId, UUID.randomUUID(), AnomalyStatus.DISMISSED))
        assertEquals(AnomalyStatus.OPEN, anomalies.findById(theirs.id)?.status)
        assertNull(service.find(userId, theirs.id))
    }

    @Test
    fun `resolving to open is refused`() = runBlocking<Unit> {
        val a = anomaly()
        assertFailsWith<IllegalArgumentException> { service.resolve(userId, a.id, AnomalyStatus.OPEN) }
    }

    @Test
    fun `every answer is audited with the before and after status`() = runBlocking<Unit> {
        val a = anomaly()
        resolve(a, AnomalyStatus.CONFIRMED_FRAUD)
        val entry = audits.findByEntity("anomaly", a.id).single()
        assertEquals(AuditActions.ANOMALY_CONFIRM_FRAUD, entry.action)
        assertEquals("\"open\"", entry.beforeState?.get("status").toString())
        assertEquals("\"confirmed_fraud\"", entry.afterState?.get("status").toString())
    }

    @Test
    fun `statuses map to feedback and audit actions`() {
        assertEquals(AlertFeedback.DISMISSED, AnomalyReviewService.feedbackFor(AnomalyStatus.CONFIRMED_SAFE))
        assertEquals(AlertFeedback.DISMISSED, AnomalyReviewService.feedbackFor(AnomalyStatus.DISMISSED))
        assertEquals(AlertFeedback.CONFIRMED, AnomalyReviewService.feedbackFor(AnomalyStatus.CONFIRMED_FRAUD))
        assertNull(AnomalyReviewService.feedbackFor(AnomalyStatus.OPEN))
        assertEquals(AuditActions.ANOMALY_CONFIRM_SAFE, AnomalyReviewService.auditAction(AnomalyStatus.CONFIRMED_SAFE))
        assertEquals(AuditActions.ANOMALY_DISMISS, AnomalyReviewService.auditAction(AnomalyStatus.DISMISSED))
        assertEquals("anomaly.resolve", AnomalyReviewService.auditAction(AnomalyStatus.OPEN))
    }

    @Test
    fun `listing returns the page and the total for the filter`() = runBlocking<Unit> {
        anomaly(); anomaly(status = AnomalyStatus.DISMISSED); anomaly(owner = UUID.randomUUID())
        val (page, total) = service.list(userId, AnomalyStatus.OPEN, null, 10)
        assertEquals(1, page.size)
        assertEquals(1L, total)
        assertEquals(2L, service.list(userId, null, null, 10).second)
    }

    @Test
    fun `a repeated false alarm stops after the user says it is normal`() = runBlocking<Unit> {
        // Dismissal replay (research §10): a monthly ₹3,000 shop at a merchant that is usually ₹2,000.
        val detector = AnomalyDetectorService(anomalies, transactions, UserCalendar(InMemoryUserRepository(), clock), tolerances)
        val start = Instant.parse("2026-01-05T06:30:00Z")
        listOf(2000, 2050, 1950, 2020, 1980).forEachIndexed { i, a ->
            transactions.save(Transaction(userId = userId, type = TransactionType.DEBIT, amount = BigDecimal(a), merchantName = "Big Bazaar",
                category = TransactionCategory.SHOPPING, transactedAt = start.plus(i * 3L, ChronoUnit.DAYS)))
        }
        val alerted = (1..4).map { month ->
            val t = transactions.save(Transaction(userId = userId, type = TransactionType.DEBIT, amount = BigDecimal(3000 + month * 10),
                merchantName = "Big Bazaar", category = TransactionCategory.SHOPPING, transactedAt = start.plus(month * 30L, ChronoUnit.DAYS)))
            detector.evaluate(t)?.also { resolve(it, AnomalyStatus.CONFIRMED_SAFE) } != null
        }
        // z ≈ 4.3 each month (log scale, 10% spread floor): over 3.5 (alert, dismissed), then under 4.5: quiet from then on.
        assertEquals(listOf(true, false, false, false), alerted, "one dismissal stops it")
        assertEquals(1, level("big bazaar"))
        assertNotNull(tolerances.find(userId, "big bazaar", TolerableRule.LARGE_AMOUNT))
    }
}
