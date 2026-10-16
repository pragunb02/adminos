package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.links.DuplicateService
import dev.adminos.api.domain.financial.links.LinkKind
import dev.adminos.api.domain.financial.links.TransactionMatcher
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
import kotlin.test.assertNull

/** AOS-207 on Postgres: V13, links, atomic merge and confirm. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossSourceDedupIntegrationTest {

    private val events = ExposedIngestEventRepository()
    private val txns = ExposedTransactionRepository()
    private val links = ExposedTransactionLinkRepository()
    private val processor = IngestProcessor(
        events,
        IngestPipeline(listOf(ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()),
            CrossSourceDedupStep(RepositoryMatchCandidates(txns, links), TransactionMatcher()), CategorizeStep(CategorizationService()))),
        RepositoryOutcomeWriter(txns, events, links) { w -> dbQuery { w() } }
    )
    private val duplicates = DuplicateService(links, txns) { w -> dbQuery { w() } }

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private fun event(user: UUID, source: IngestSource, ref: String, at: Instant, rrn: String? = null) = IngestEvent(
        userId = user, source = source, sourceRef = ref, occurredAt = at.truncatedTo(ChronoUnit.MILLIS), direction = IngestDirection.DEBIT,
        amount = BigDecimal("349.00"), merchantRaw = "ZOMATO ORDER", accountHint = "1234", paymentMethod = PaymentMethod.UPI, paymentRef = rrn
    )

    private suspend fun drain() { while (processor.processBatch() > 0) {} }

    @Test
    fun `sms and statement for the same payment become one transaction with a merge link`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val t0 = Instant.now().minus(2, ChronoUnit.DAYS)
        events.saveNew(listOf(event(user, IngestSource.SMS, "sms-1", t0))); drain()
        events.saveNew(listOf(event(user, IngestSource.STATEMENT, "st-1", t0.plus(3, ChronoUnit.HOURS), rrn = "123456789012"))); drain()

        val t = txns.findByUserId(user, limit = 10).single()
        assertEquals("123456789012", t.paymentRef, "enriched from the statement")
        assertEquals(setOf("statement"), links.mergedSources(listOf(t.id))[t.id])
    }

    @Test
    fun `a confirmed suggestion deletes the duplicate atomically and keeps the link`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val t0 = Instant.now().minus(1, ChronoUnit.DAYS)
        events.saveNew(listOf(event(user, IngestSource.SMS, "sms-1", t0))); drain()
        events.saveNew(listOf(event(user, IngestSource.MANUAL, "man-1", t0.plus(5, ChronoUnit.HOURS)).copy(accountHint = null))); drain()
        assertEquals(2, txns.findByUserId(user, limit = 10).size)

        val s = duplicates.suggestions(user).single()
        duplicates.confirm(user, s.link.id)
        assertEquals(1, txns.findByUserId(user, limit = 10).size)
        val link = links.findById(s.link.id)!!
        assertEquals(LinkKind.MERGED, link.kind); assertNull(link.relatedTransactionId)
        assertEquals(setOf("manual"), links.mergedSources(listOf(s.kept.id))[s.kept.id])
    }
}
