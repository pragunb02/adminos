package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.agent.AlertFeedback
import dev.adminos.api.domain.agent.Anomaly
import dev.adminos.api.domain.agent.AnomalyResolution
import dev.adminos.api.domain.agent.AnomalyReviewService
import dev.adminos.api.domain.agent.AnomalyStatus
import dev.adminos.api.domain.agent.AnomalyType
import dev.adminos.api.domain.agent.TolerableRule
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQueryReadCommitted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** AOS-713 on Postgres: V33 tolerances and the anomaly columns that feed them. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnomalyToleranceIntegrationTest {
    private val tolerances = ExposedAnomalyToleranceRepository()
    private val anomalies = ExposedAnomalyRepository()
    private val transactions = ExposedTransactionRepository()
    private val at = Instant.parse("2026-10-17T06:30:00Z").truncatedTo(ChronoUnit.MICROS)

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    @Test
    fun `a tolerance is created on the first answer and moves within its cap`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        assertNull(tolerances.find(user, "swiggy", TolerableRule.LARGE_AMOUNT))
        repeat(5) { tolerances.record(user, "swiggy", TolerableRule.LARGE_AMOUNT, AlertFeedback.DISMISSED, at) }
        val capped = tolerances.record(user, "swiggy", TolerableRule.LARGE_AMOUNT, AlertFeedback.CONFIRMED, at.plusSeconds(1))
        assertEquals(2, capped.level.steps)
        val stored = tolerances.find(user, "swiggy", TolerableRule.LARGE_AMOUNT)
        assertEquals(capped, stored)
        assertEquals(5, stored?.dismissals)
        assertEquals(1, stored?.confirmations)
        assertEquals(at.plusSeconds(1), stored?.updatedAt)
        assertNull(tolerances.find(user, "zomato", TolerableRule.LARGE_AMOUNT), "per merchant")
    }

    @Test
    fun `answers at the same moment all count`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        (1..6).map { async(Dispatchers.IO) { tolerances.record(user, "big bazaar", TolerableRule.LARGE_AMOUNT, AlertFeedback.DISMISSED, at) } }.awaitAll()
        val stored = tolerances.find(user, "big bazaar", TolerableRule.LARGE_AMOUNT)
        assertEquals(6, stored?.dismissals)
        assertEquals(3, stored?.level?.steps)
    }

    @Test
    fun `an anomaly keeps the rules that fired and its merchant`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val t = transactions.save(Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal("4800.00"), merchantName = "Swiggy", transactedAt = at))
        val saved = anomalies.save(Anomaly(userId = user, transactionId = t.id, type = AnomalyType.LARGE_AMOUNT, confidenceScore = 0.75,
            reason = "₹4,800 is more than usual at Swiggy", rulesFired = setOf("odd_hours", "large_amount"), merchantKey = "swiggy"))
        val found = anomalies.findById(saved.id)
        assertEquals(setOf("large_amount", "odd_hours"), found?.rulesFired)
        assertEquals("swiggy", found?.merchantKey)
        val plain = anomalies.save(Anomaly(userId = user, transactionId = transactions.save(t.copy(id = java.util.UUID.randomUUID())).id,
            type = AnomalyType.CARD_TESTING, confidenceScore = 0.9, reason = "₹1"))
        assertEquals(emptySet(), anomalies.findById(plain.id)?.rulesFired)
        assertNull(anomalies.findById(plain.id)?.merchantKey)
    }

    @Test
    fun `dismissing an alert on Postgres resolves it and raises the tolerance in one unit of work`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val t = transactions.save(Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal("3000.00"), merchantName = "Big Bazaar", transactedAt = at))
        val a = anomalies.save(Anomaly(userId = user, transactionId = t.id, type = AnomalyType.LARGE_AMOUNT, confidenceScore = 0.7, reason = "r",
            rulesFired = setOf("large_amount"), merchantKey = "big bazaar"))
        val review = AnomalyReviewService(anomalies, transactions, tolerances, AuditService(ExposedAuditRepository(), CoroutineScope(Dispatchers.Unconfined)),
            java.time.Clock.fixed(at, java.time.ZoneOffset.UTC)) { work -> dbQueryReadCommitted { work() } }
        val outcome = assertIs<AnomalyResolution.Resolved>(review.resolve(user, a.id, AnomalyStatus.CONFIRMED_SAFE))
        assertEquals(AnomalyStatus.CONFIRMED_SAFE, anomalies.findById(a.id)?.status)
        assertEquals(1, outcome.tolerances.single().level.steps)
        assertEquals(1, tolerances.find(user, "big bazaar", TolerableRule.LARGE_AMOUNT)?.level?.steps)
    }
}
