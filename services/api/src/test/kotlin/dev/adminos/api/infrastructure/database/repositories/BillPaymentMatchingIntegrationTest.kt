package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.*
import dev.adminos.api.domain.financial.links.TransferDetector
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestSource
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
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals

/** AOS-508 on Postgres: a card-bill payment SMS pays the card bill; the sweep pays a bill that came after its payment. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BillPaymentMatchingIntegrationTest {
    private val events = ExposedIngestEventRepository()
    private val txns = ExposedTransactionRepository()
    private val links = ExposedTransactionLinkRepository()
    private val bills = ExposedBillRepository()
    private val billing = BillTrackingService(bills, txns)
    private lateinit var user: UUID

    private val processor by lazy {
        IngestProcessor(
            events,
            IngestPipeline(listOf(ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()), CategorizeStep(CategorizationService(ExposedCategoryRuleRepository())),
                TransferStep(RepositoryMatchCandidates(txns, links), TransferDetector()))),
            RepositoryOutcomeWriter(txns, events, links) { w -> dbQuery { w() } },
            listeners = listOf(TransactionCreatedListener { if (it.userId == user) BillPaymentListener(billing).onCreated(it) })
        )
    }

    @BeforeAll
    fun start() {
        PostgresTestDatabase.ensureStarted()
        user = runBlocking { PostgresTestDatabase.newUser() }
    }

    @Test
    fun `an HDFC card bill payment SMS marks the card bill paid, even though it is a transfer`() = runBlocking<Unit> {
        val today = LocalDate.now(BillPaymentMatcher.ZONE)
        val card = bills.save(Bill(userId = user, billType = BillType.CREDIT_CARD, billerName = "HDFC Bank Credit Card", accountRef = "XXXX4521",
            amount = BigDecimal("18450.00"), minimumDue = BigDecimal("922.50"), dueDate = today.plusDays(5)))
        val event = IngestEvent(userId = user, source = IngestSource.SMS, sourceRef = UUID.randomUUID().toString(), occurredAt = Instant.now().minusSeconds(300),
            direction = IngestDirection.DEBIT, amount = BigDecimal("18450.00"), merchantRaw = "CC PAYMENT XX4521 BILLDESK", accountHint = "1234",
            paymentMethod = PaymentMethod.NETBANKING)
        events.saveNew(listOf(event))
        while (processor.processBatch() > 0) {}

        val t = txns.findByIngestEventId(event.id)!!
        assertEquals(TransactionNature.TRANSFER, t.nature, "card bill payments aren't spending (AOS-208)")
        val paid = bills.findById(card.id)!!
        assertEquals(BillStatus.PAID, paid.status)
        assertEquals(t.id, paid.paymentTxnId)
        assertEquals("account_number", paid.metadata?.get("matchedBy"))
    }

    @Test
    fun `the sweep finds the payment made before the bill was detected`() = runBlocking<Unit> {
        val sweepUser = PostgresTestDatabase.newUser()
        txns.save(Transaction(userId = sweepUser, type = TransactionType.DEBIT, amount = BigDecimal("1799.00"), merchantName = "Airtel",
            transactedAt = Instant.now().minus(2, ChronoUnit.DAYS)))
        val late = bills.save(Bill(userId = sweepUser, billType = BillType.MOBILE, billerName = "Airtel Postpaid", amount = BigDecimal("1799.00"),
            dueDate = LocalDate.now(BillPaymentMatcher.ZONE).plusDays(4)))
        assertEquals(1, billing.matchEarlierPayments(listOf(late)))
        assertEquals(BillStatus.PAID, bills.findById(late.id)!!.status)
        assertEquals(0, billing.matchEarlierPayments(listOf(bills.findById(late.id)!!)), "already paid")
    }
}
