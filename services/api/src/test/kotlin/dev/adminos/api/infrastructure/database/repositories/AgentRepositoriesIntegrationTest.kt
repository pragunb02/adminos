package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.agent.*
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.BriefingsTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentRepositoriesIntegrationTest {

    private val anomalies = ExposedAnomalyRepository()
    private val briefings = ExposedBriefingRepository()
    private val insights = ExposedInsightRepository()
    private val transactions = ExposedTransactionRepository()

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private suspend fun txnId(userId: UUID) =
        transactions.save(Transaction(userId = userId, type = TransactionType.DEBIT, amount = BigDecimal("1.00"), transactedAt = Instant.now())).id

    @Test
    fun `a briefing is found by its week, with its structured facts (AOS-506)`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val repo = ExposedBriefingRepository()
        val week = java.time.LocalDate.of(2026, 10, 5)
        val saved = repo.save(Briefing(userId = userId, periodStart = week, periodEnd = week.plusDays(6), content = "Last week…",
            contentStructured = mapOf("spent" to "4100.50", "topCategories" to listOf(mapOf("category" to "food", "spent" to "2000.00"))),
            totalSpent = java.math.BigDecimal("4100.50"), modelUsed = "deterministic", promptVersion = "weekly-v1", tokensUsed = 0))
        val found = repo.findByUserAndPeriod(userId, week, BriefingType.WEEKLY)
        assertEquals(saved.id, found?.id)
        assertEquals("4100.50", found?.contentStructured?.get("spent"))
        assertEquals(null, repo.findByUserAndPeriod(userId, week.plusWeeks(1), BriefingType.WEEKLY))
        assertEquals(null, repo.findByUserAndPeriod(userId, week, BriefingType.MONTHLY))
    }

    @Test
    fun `a briefing's narration round-trips every outcome, and the template stays (AOS-716)`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val week = java.time.LocalDate.of(2026, 10, 5)
        val saved = briefings.save(Briefing(userId = userId, periodStart = week, periodEnd = week.plusDays(6), content = "Last week you spent ₹4,100.50.",
            modelUsed = "deterministic", promptVersion = "weekly-v1", tokensUsed = 0))
        assertEquals(BriefingNarration.None, briefings.findById(saved.id)?.narration)
        val outcomes = listOf(
            BriefingNarration.Narrated("You spent ₹4,100.50 last week.", "claude-haiku-5-5"),
            BriefingNarration.Rejected(dev.adminos.api.domain.ai.AiReasonCode("ungrounded_number")),
            BriefingNarration.Unavailable(dev.adminos.api.domain.ai.AiReasonCode("cap_reached")),
            BriefingNarration.None
        )
        for (n in outcomes) {
            val at = Instant.parse("2026-10-12T03:31:00Z")
            briefings.updateNarration(saved.id, n, at)
            val found = assertNotNull(briefings.findById(saved.id))
            assertEquals(n, found.narration)
            assertEquals("Last week you spent ₹4,100.50.", found.content)
            assertEquals(at, found.updatedAt)
        }
        val narrated = briefings.save(Briefing(userId = userId, periodStart = week.plusWeeks(1), periodEnd = week.plusWeeks(1).plusDays(6), content = "t",
            modelUsed = "deterministic", promptVersion = "weekly-v1", tokensUsed = 0, narration = outcomes.first()))
        assertEquals(outcomes.first(), briefings.findById(narrated.id)?.narration, "saved with a narration too")
    }

    @Test
    fun `the database refuses a narration that breaks its shape (V90)`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val b = briefings.save(Briefing(userId = userId, periodStart = java.time.LocalDate.of(2026, 9, 7), periodEnd = java.time.LocalDate.of(2026, 9, 13),
            content = "t", modelUsed = "deterministic", promptVersion = "weekly-v1", tokensUsed = 0))
        val tooLong = BriefingNarration.Narrated("x".repeat(901), "claude-haiku-5-5")
        assertFailsWith<ExposedSQLException> { briefings.updateNarration(b.id, tooLong, Instant.now()) }
        assertFailsWith<ExposedSQLException> {
            dbQuery { BriefingsTable.update({ BriefingsTable.id eq b.id }) { it[BriefingsTable.narrationStatus] = "narrated" } }
        }
        assertEquals(BriefingNarration.None, briefings.findById(b.id)?.narration)
    }

    @Test
    fun `anomaly round-trips every type, status and resolver`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        for (type in AnomalyType.entries) {
            // One anomaly per transaction (V20, AOS-505)
            val a = anomalies.save(Anomaly(userId = userId, transactionId = txnId(userId), type = type, confidenceScore = 0.8, reason = "test $type"))
            assertEquals(type, anomalies.findById(a.id)?.type)
        }
        val a = anomalies.findByUserId(userId, limit = 50).first()
        for (status in AnomalyStatus.entries) for (resolver in AnomalyResolver.entries) {
            anomalies.update(a.copy(status = status, resolvedBy = resolver, resolvedAt = Instant.now(), agentExplanation = "why"))
            val found = assertNotNull(anomalies.findById(a.id))
            assertEquals(status, found.status)
            assertEquals(resolver, found.resolvedBy)
        }
        assertEquals(AnomalyType.entries.size.toLong(), anomalies.countByUserId(userId))
    }

    @Test
    fun `briefing round-trips structured content and top categories`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val start = LocalDate.now().minusDays(7)
        val b = briefings.save(
            Briefing(
                userId = userId, periodStart = start, periodEnd = LocalDate.now(), content = "You spent ₹4,200 this week.",
                contentStructured = mapOf("summary" to "ok"), totalSpent = BigDecimal("4200.00"), totalIncome = BigDecimal("0.00"),
                topCategories = listOf(CategoryAmount("food", BigDecimal("2000.00")), CategoryAmount("transport", BigDecimal("800.00"))),
                subscriptionsFlagged = 1, anomaliesDetected = 2, billsUpcoming = 3,
                modelUsed = "claude", promptVersion = "v1", tokensUsed = 1234, generationMs = 900
            )
        )
        val found = assertNotNull(briefings.findById(b.id))
        assertEquals(b.content, found.content)
        assertEquals("ok", found.contentStructured?.get("summary"))
        assertEquals(listOf("food", "transport"), found.topCategories?.map { it.category })
        assertEquals(0, BigDecimal("4200.00").compareTo(found.totalSpent))
        assertEquals(start, found.periodStart)
        assertEquals(b.id, briefings.findLatestByUserId(userId)?.id)
    }

    @Test
    fun `every briefing type and status persists`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        var day = 0L
        for (type in BriefingType.entries) for (status in BriefingStatus.entries) {
            // unique (user_id, period_start, type)
            val start = LocalDate.now().minusDays(100 + day++)
            val b = briefings.save(Briefing(userId = userId, periodStart = start, periodEnd = start.plusDays(6), type = type, status = status,
                content = "c", modelUsed = "m", promptVersion = "v", tokensUsed = 1))
            val found = assertNotNull(briefings.findById(b.id))
            assertEquals(type, found.type)
            assertEquals(status, found.status)
        }
        assertEquals(day, briefings.countByUserId(userId))
    }

    @Test
    fun `insight round-trips every type, action, severity and status`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val briefing = briefings.save(Briefing(userId = userId, periodStart = LocalDate.now().minusDays(7), periodEnd = LocalDate.now(),
            content = "c", modelUsed = "m", promptVersion = "v", tokensUsed = 1))
        var n = 0
        for (type in InsightType.entries) for (action in InsightActionType.entries) {
            insights.save(Insight(userId = userId, briefingId = briefing.id, type = type, title = "t", body = "b",
                actionType = action, actionPayload = mapOf("k" to "v"), severity = Severity.entries[n % Severity.entries.size],
                status = InsightStatus.entries[n % InsightStatus.entries.size]))
            n++
        }
        val found = insights.findByBriefingId(briefing.id)
        assertEquals(n, found.size)
        assertEquals(InsightType.entries.toSet(), found.map { it.type }.toSet())
        assertEquals(InsightActionType.entries.toSet(), found.map { it.actionType }.toSet())
        assertEquals(Severity.entries.toSet(), found.map { it.severity }.toSet())
        assertEquals("v", found.first().actionPayload?.get("k"))
        assertEquals(found.count { it.status == InsightStatus.PENDING }, insights.findByUserId(userId, InsightStatus.PENDING).size)
    }
}
