package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.ingestion.events.*
import dev.adminos.api.domain.ingestion.pipeline.*
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQuery
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** D-34 pipeline on real Postgres: claiming, atomic outcomes, end-to-end. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestPipelineIntegrationTest {

    private val events = ExposedIngestEventRepository()
    private val transactions = ExposedTransactionRepository()
    private val writer = RepositoryOutcomeWriter(transactions, events) { work -> dbQuery { work() } }
    private val pipeline = IngestPipeline(listOf(ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()), CategorizeStep(CategorizationService())))

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private fun event(userId: UUID, ref: String = UUID.randomUUID().toString()) = IngestEvent(
        userId = userId, source = IngestSource.SMS, sourceRef = ref, occurredAt = Instant.now().minus(1, ChronoUnit.HOURS),
        direction = IngestDirection.DEBIT, amount = BigDecimal("349.00"), merchantRaw = "ZOMATO ORDER 991", accountHint = "1234",
        paymentMethod = PaymentMethod.UPI
    )

    @Test
    fun `end to end - intake to categorised transaction in Postgres`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val service = IngestEventService(events)
        assertEquals(2, service.ingest(listOf(event(userId, "e2e-1"), event(userId, "e2e-2"))).accepted)

        val processor = IngestProcessor(events, pipeline, writer)
        while (processor.processBatch() > 0) { /* drain, including other tests' leftovers */ }

        val txns = transactions.findByUserId(userId, limit = 10)
        assertEquals(2, txns.size)
        assertTrue(txns.all { it.merchantName == "Zomato" && it.category == TransactionCategory.FOOD && it.ingestEventId != null })
        assertTrue(events.findByUser(userId).all { it.status == IngestEventStatus.PROCESSED && it.processedAt != null })
    }

    @Test
    fun `concurrent claims never return the same event`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        events.saveNew((1..40).map { event(userId) })
        val claims = (1..4).map { async(Dispatchers.IO) { events.claim(15, staleBefore = Instant.EPOCH) } }.awaitAll()
        val mine = claims.flatten().filter { it.userId == userId }.map { it.id }
        assertEquals(mine.size, mine.toSet().size, "an event was claimed twice")
        assertEquals(40, mine.size)
    }

    @Test
    fun `outcome is atomic and idempotent - one transaction per event`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val e = event(userId); events.saveNew(listOf(e))
        val txn = (pipeline.run(e) as PipelineOutcome.Created).transaction
        writer.processed(e, txn)
        writer.processed(e, txn.copy(id = UUID.randomUUID())) // re-claimed after a crash
        assertEquals(1, transactions.findByUserId(userId, limit = 10).size)
        assertEquals(IngestEventStatus.PROCESSED, events.findByUser(userId).single().status)
    }

    @Test
    fun `the database itself rejects a second transaction for the same event`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val e = event(userId); events.saveNew(listOf(e))
        val txn = (pipeline.run(e) as PipelineOutcome.Created).transaction
        transactions.save(txn)
        assertFailsWith<Exception> { transactions.save(txn.copy(id = UUID.randomUUID())) }
    }
}
