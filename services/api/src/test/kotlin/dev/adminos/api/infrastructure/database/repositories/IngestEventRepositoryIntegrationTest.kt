package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.ingestion.events.*
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestEventRepositoryIntegrationTest {

    private val repo = ExposedIngestEventRepository()

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private fun event(userId: UUID, ref: String = UUID.randomUUID().toString(), source: IngestSource = IngestSource.SMS) = IngestEvent(
        userId = userId, source = source, sourceRef = ref, occurredAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS),
        direction = IngestDirection.DEBIT, amount = BigDecimal("12345678.90"), merchantRaw = "AMAZON PAY", accountHint = "4321",
        paymentMethod = PaymentMethod.UPI, upiVpa = "amazon@apl", metadata = mapOf("parser" to "hdfc-v1")
    )

    @Test
    fun `round-trips every field`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val e = event(userId)
        assertEquals(1, repo.saveNew(listOf(e)))
        val found = repo.findByUser(userId).single()
        assertEquals(e.copy(receivedAt = found.receivedAt), found.copy(occurredAt = found.occurredAt.truncatedTo(ChronoUnit.MILLIS)))
    }

    @Test
    fun `the database enforces one event per user, source and sourceRef`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        assertEquals(1, repo.saveNew(listOf(event(userId, "dup"))))
        assertEquals(0, repo.saveNew(listOf(event(userId, "dup"))))
        assertEquals(1, repo.saveNew(listOf(event(PostgresTestDatabase.newUser(), "dup"))), "other users are independent")
    }

    @Test
    fun `every source, direction and payment method persists`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        for (s in IngestSource.entries) for (d in IngestDirection.entries) for (pm in PaymentMethod.entries)
            repo.saveNew(listOf(event(userId, source = s).copy(direction = d, paymentMethod = pm)))
        val all = repo.findByUser(userId, limit = 500)
        assertEquals(IngestSource.entries.toSet(), all.map { it.source }.toSet())
        assertEquals(IngestDirection.entries.toSet(), all.map { it.direction }.toSet())
        assertEquals(PaymentMethod.entries.toSet(), all.mapNotNull { it.paymentMethod }.toSet())
    }

    @Test
    fun `pending events come back oldest first`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val t0 = Instant.now().minus(1, ChronoUnit.DAYS)
        val older = event(userId).copy(receivedAt = t0)
        val newer = event(userId).copy(receivedAt = t0.plusSeconds(60))
        repo.saveNew(listOf(newer, older))
        val pending = repo.findPending(limit = 1000).filter { it.userId == userId }
        assertEquals(listOf(older.id, newer.id), pending.map { it.id })
    }
}
