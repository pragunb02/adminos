package dev.adminos.api.domain.agent

import dev.adminos.api.domain.common.CursorCodec
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory agent repositories and response DTO mapping (AOS-312). */
class AgentRepositoriesDtosCoverageTest {
    private val userId = UUID.randomUUID()
    private val t0 = Instant.parse("2026-10-10T06:30:00Z")

    private fun anomaly(status: AnomalyStatus = AnomalyStatus.OPEN, createdAt: Instant = t0, user: UUID = userId) = Anomaly(
        userId = user, transactionId = UUID.randomUUID(), type = AnomalyType.FOREIGN_CURRENCY,
        confidenceScore = 0.8, reason = "Foreign currency charge in USD", status = status, createdAt = createdAt
    )

    private fun briefing(createdAt: Instant = t0, user: UUID = userId) = Briefing(
        userId = user, periodStart = LocalDate.of(2026, 10, 1), periodEnd = LocalDate.of(2026, 10, 31),
        type = BriefingType.MONTHLY, content = "October summary", modelUsed = "claude", promptVersion = "v2",
        tokensUsed = 1200, createdAt = createdAt
    )

    private fun insight(briefingId: UUID?, status: InsightStatus = InsightStatus.PENDING, createdAt: Instant = t0) = Insight(
        userId = userId, briefingId = briefingId, type = InsightType.BILL_DUE, title = "HDFC card bill due",
        body = "₹18,240 due on 20 Oct", status = status, createdAt = createdAt
    )

    // ── InMemoryAnomalyRepository ──

    @Test
    fun `anomaly repository finds by id and returns null for unknown ids`() = runBlocking<Unit> {
        val repo = InMemoryAnomalyRepository()
        val a = repo.save(anomaly())

        assertEquals(a, repo.findById(a.id))
        assertNull(repo.findById(UUID.randomUUID()))
    }

    @Test
    fun `anomaly repository lists a user's anomalies newest first, filtered and limited`() = runBlocking<Unit> {
        val repo = InMemoryAnomalyRepository()
        repo.save(anomaly(createdAt = t0))
        repo.save(anomaly(createdAt = t0.plusSeconds(10), status = AnomalyStatus.DISMISSED))
        repo.save(anomaly(createdAt = t0.plusSeconds(20)))
        repo.save(anomaly(user = UUID.randomUUID()))

        assertEquals(listOf(t0.plusSeconds(20), t0.plusSeconds(10)), repo.findByUserId(userId, limit = 2).map { it.createdAt })
        assertEquals(listOf(t0.plusSeconds(20), t0), repo.findByUserId(userId, status = AnomalyStatus.OPEN).map { it.createdAt })
        val cursor = CursorCodec.encode(t0.plusSeconds(20).toString())
        assertEquals(listOf(t0.plusSeconds(10), t0), repo.findByUserId(userId, cursor = cursor).map { it.createdAt })
    }

    @Test
    fun `anomaly repository counts a user's anomalies with and without a status`() = runBlocking<Unit> {
        val repo = InMemoryAnomalyRepository()
        repo.save(anomaly())
        repo.save(anomaly(status = AnomalyStatus.CONFIRMED_FRAUD))

        assertEquals(2L, repo.countByUserId(userId))
        assertEquals(1L, repo.countByUserId(userId, AnomalyStatus.CONFIRMED_FRAUD))
        assertEquals(0L, repo.countByUserId(UUID.randomUUID()))
    }

    @Test
    fun `anomaly repository update stores the change with a fresh updatedAt`() = runBlocking<Unit> {
        val repo = InMemoryAnomalyRepository()
        val a = repo.save(anomaly().copy(updatedAt = t0))

        val updated = repo.update(a.copy(status = AnomalyStatus.CONFIRMED_SAFE))

        assertEquals(AnomalyStatus.CONFIRMED_SAFE, repo.findById(a.id)!!.status)
        assertTrue(updated.updatedAt.isAfter(t0))
    }

    // ── InMemoryBriefingRepository ──

    @Test
    fun `briefing repository finds by id and the latest by user`() = runBlocking<Unit> {
        val repo = InMemoryBriefingRepository()
        val old = repo.save(briefing(createdAt = t0))
        val latest = repo.save(briefing(createdAt = t0.plusSeconds(60)))

        assertEquals(old, repo.findById(old.id))
        assertNull(repo.findById(UUID.randomUUID()))
        assertEquals(latest, repo.findLatestByUserId(userId))
        assertNull(repo.findLatestByUserId(UUID.randomUUID()))
    }

    @Test
    fun `briefing repository lists newest first after a cursor and counts per user`() = runBlocking<Unit> {
        val repo = InMemoryBriefingRepository()
        repo.save(briefing(createdAt = t0))
        repo.save(briefing(createdAt = t0.plusSeconds(60)))
        repo.save(briefing(user = UUID.randomUUID()))

        assertEquals(listOf(t0.plusSeconds(60), t0), repo.findByUserId(userId).map { it.createdAt })
        val cursor = CursorCodec.encode(t0.plusSeconds(60).toString())
        assertEquals(listOf(t0), repo.findByUserId(userId, cursor = cursor).map { it.createdAt })
        assertEquals(2L, repo.countByUserId(userId))
    }

    // ── InMemoryInsightRepository ──

    @Test
    fun `insight repository finds a briefing's insights newest first`() = runBlocking<Unit> {
        val repo = InMemoryInsightRepository()
        val briefingId = UUID.randomUUID()
        repo.save(insight(briefingId, createdAt = t0))
        repo.save(insight(briefingId, createdAt = t0.plusSeconds(5)))
        repo.save(insight(null))

        assertEquals(listOf(t0.plusSeconds(5), t0), repo.findByBriefingId(briefingId).map { it.createdAt })
    }

    @Test
    fun `insight repository finds a user's insights optionally by status`() = runBlocking<Unit> {
        val repo = InMemoryInsightRepository()
        repo.save(insight(null, status = InsightStatus.PENDING, createdAt = t0))
        repo.save(insight(null, status = InsightStatus.SEEN, createdAt = t0.plusSeconds(5)))

        assertEquals(listOf(InsightStatus.SEEN, InsightStatus.PENDING), repo.findByUserId(userId).map { it.status })
        assertEquals(listOf(InsightStatus.SEEN), repo.findByUserId(userId, InsightStatus.SEEN).map { it.status })
    }

    // ── DTOs ──

    @Test
    fun `anomaly response maps an open anomaly with null resolution fields`() {
        val a = anomaly()

        val r = AnomalyResponse.from(a)

        assertEquals("foreign_currency", r.type)
        assertEquals("open", r.status)
        assertNull(r.resolvedAt); assertNull(r.resolvedBy); assertNull(r.agentExplanation)
    }

    @Test
    fun `anomaly response maps a resolved anomaly in lowercase`() {
        val resolvedAt = t0.plusSeconds(100)
        val a = anomaly(status = AnomalyStatus.CONFIRMED_SAFE)
            .copy(resolvedAt = resolvedAt, resolvedBy = AnomalyResolver.AGENT, agentExplanation = "Known merchant")

        val r = AnomalyResponse.from(a)

        assertEquals("confirmed_safe", r.status)
        assertEquals(resolvedAt.toString(), r.resolvedAt)
        assertEquals("agent", r.resolvedBy)
        assertEquals("Known merchant", r.agentExplanation)
    }

    @Test
    fun `briefing response maps totals, categories and insights`() {
        val b = briefing().copy(
            totalSpent = BigDecimal("12450.50"), totalIncome = BigDecimal("85000"),
            topCategories = listOf(CategoryAmount("food", BigDecimal("4200.25"))),
            subscriptionsFlagged = 2, anomaliesDetected = 1, billsUpcoming = 3, status = BriefingStatus.DELIVERED
        )
        val entity = UUID.randomUUID()
        val i = insight(b.id).copy(entityId = entity, severity = Severity.CRITICAL, actionType = InsightActionType.PAY_BILL)

        val r = BriefingResponse.from(b, listOf(i))

        assertEquals("monthly", r.type); assertEquals("delivered", r.status)
        assertEquals("12450.50", r.totalSpent); assertEquals("85000.00", r.totalIncome)
        assertEquals(listOf(CategoryAmountResponse("food", "4200.25")), r.topCategories)
        assertEquals(2, r.subscriptionsFlagged); assertEquals(1, r.anomaliesDetected); assertEquals(3, r.billsUpcoming)
        val ir = r.insights!!.single()
        assertEquals("bill_due", ir.type); assertEquals("critical", ir.severity); assertEquals("pay_bill", ir.actionType)
        assertEquals(entity.toString(), ir.entityId); assertEquals("pending", ir.status)
    }

    @Test
    fun `briefing response leaves absent totals, categories and insights null`() {
        val r = BriefingResponse.from(briefing())

        assertNull(r.totalSpent); assertNull(r.totalIncome); assertNull(r.topCategories); assertNull(r.insights)
        assertEquals("2026-10-01", r.periodStart); assertEquals("2026-10-31", r.periodEnd)
    }

    @Test
    fun `insight response leaves a missing entity id null`() {
        assertNull(InsightResponse.from(insight(null)).entityId)
    }

    @Test
    fun `request dtos default and carry their values`() {
        assertNull(SubscriptionDismissRequest().reason)
        assertEquals("still using it", SubscriptionDismissRequest("still using it").reason)
        assertEquals("dismissed", AnomalyResolveRequest("dismissed").status)
        assertEquals("queued", CancellationQueuedResponse("s", "queued", "m").status)
    }

    @Test
    fun `model defaults give each new anomaly, briefing and insight a fresh id and default state`() {
        val a1 = anomaly(); val a2 = anomaly()
        assertNotEquals(a1.id, a2.id)
        assertEquals(AnomalyStatus.OPEN, a1.status)
        val b = Briefing(userId = userId, periodStart = LocalDate.of(2026, 10, 5), periodEnd = LocalDate.of(2026, 10, 11),
            content = "c", modelUsed = "m", promptVersion = "p", tokensUsed = 1)
        assertEquals(BriefingType.WEEKLY, b.type); assertEquals(BriefingStatus.GENERATED, b.status)
        assertNull(b.generationMs); assertNull(b.contentStructured)
        val i = Insight(userId = userId, type = InsightType.ANOMALY, title = "t", body = "b")
        assertEquals(Severity.INFO, i.severity); assertEquals(InsightActionType.NONE, i.actionType)
        assertNull(i.briefingId)
    }
}
