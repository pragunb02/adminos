package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.links.*
import dev.adminos.api.domain.ingestion.events.*
import dev.adminos.api.domain.ingestion.pipeline.*
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQuery
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

/** AOS-209 on Postgres: findInWindow, refund links, and refund-netted spending. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefundReversalIntegrationTest {

    private val events = ExposedIngestEventRepository()
    private val txns = ExposedTransactionRepository()
    private val links = ExposedTransactionLinkRepository()
    private val candidates = RepositoryMatchCandidates(txns, links)
    private val processor = IngestProcessor(
        events,
        IngestPipeline(listOf(ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()),
            CrossSourceDedupStep(candidates, TransactionMatcher()), CategorizeStep(CategorizationService()),
            TransferStep(candidates, TransferDetector()), RefundReversalStep(txns, links, RefundReversalDetector()))),
        RepositoryOutcomeWriter(txns, events, links) { w -> dbQuery { w() } }
    )

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private fun event(user: UUID, ref: String, dir: IngestDirection, at: Instant, amount: String, raw: String) = IngestEvent(
        userId = user, source = IngestSource.SMS, sourceRef = ref, occurredAt = at.truncatedTo(ChronoUnit.MILLIS), direction = dir,
        amount = BigDecimal(amount), merchantRaw = raw, accountHint = "1234"
    )

    private suspend fun drain() { while (processor.processBatch() > 0) {} }

    @Test
    fun `findInWindow honours type, window and amount bounds`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val t0 = Instant.now().minus(10, ChronoUnit.DAYS)
        events.saveNew(listOf(
            event(user, "a", IngestDirection.DEBIT, t0, "100.00", "SHOP A"),
            event(user, "b", IngestDirection.DEBIT, t0.plus(1, ChronoUnit.DAYS), "500.00", "SHOP B"),
            event(user, "c", IngestDirection.CREDIT, t0.plus(1, ChronoUnit.DAYS), "500.00", "SALARY ACME"),
        )); drain()
        val hits = txns.findInWindow(user, TransactionType.DEBIT, t0.minus(1, ChronoUnit.HOURS), t0.plus(2, ChronoUnit.DAYS), minAmount = BigDecimal("200.00"))
        assertEquals(listOf(BigDecimal("500.00")), hits.map { it.amount })
    }

    @Test
    fun `a refund is linked and nets its category's spending`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val t0 = Instant.now().minus(20, ChronoUnit.DAYS)
        events.saveNew(listOf(event(user, "d", IngestDirection.DEBIT, t0, "999.00", "AMAZON PAY"))); drain()
        events.saveNew(listOf(event(user, "c", IngestDirection.CREDIT, t0.plus(6, ChronoUnit.DAYS), "300.00", "AMAZON REFUND"))); drain()

        val refund = txns.findByUserId(user, limit = 10).single { it.type == TransactionType.CREDIT }
        assertEquals(TransactionNature.REFUND, refund.nature)
        assertEquals(LinkRelation.REFUND, links.findByTransaction(refund.id).single().relation)
        val spend = txns.spendingByCategory(user, t0.minus(1, ChronoUnit.DAYS), Instant.now())
        assertEquals(0, BigDecimal("699.00").compareTo(spend[TransactionCategory.SHOPPING]))
    }
}
