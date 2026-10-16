package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.ingestion.cash.CashEntry
import dev.adminos.api.domain.ingestion.cash.CashIntake
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.pipeline.*
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQuery
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** AOS-204 on Postgres: V16 carries the chosen category through intake → pipeline → transaction. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CashIntakeIntegrationTest {
    private val events = ExposedIngestEventRepository()
    private val txns = ExposedTransactionRepository()
    private val links = ExposedTransactionLinkRepository()
    private val intake = CashIntake(IngestEventService(events))
    private val processor = IngestProcessor(
        events,
        IngestPipeline(listOf(ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()), CategorizeStep(CategorizationService(ExposedCategoryRuleRepository())))),
        RepositoryOutcomeWriter(txns, events, links) { w -> dbQuery { w() } }
    )

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    @Test
    fun `a cash entry becomes a user-categorised cash spend, once`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val ref = UUID.randomUUID().toString()
        intake.add(user, CashEntry(ref, BigDecimal("120.00"), TransactionCategory.TRANSPORT, note = "auto rickshaw"))
        assertFalse(intake.add(user, CashEntry(ref, BigDecimal("120.00"), TransactionCategory.TRANSPORT)))
        while (processor.processBatch() > 0) {}
        val t = txns.findByUserId(user, limit = 10).single()
        assertEquals(TransactionCategory.TRANSPORT, t.category); assertEquals("user", t.categorySource)
        assertEquals(PaymentMethod.CASH, t.paymentMethod)
    }
}
