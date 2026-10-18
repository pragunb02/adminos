package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.*
import dev.adminos.api.domain.financial.links.TransferDetector
import dev.adminos.api.domain.financial.paylater.PayLaterBills
import dev.adminos.api.domain.financial.paylater.PayLaterService
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.pipeline.*
import dev.adminos.api.domain.ingestion.sms.SmsIntake
import dev.adminos.api.domain.ingestion.sms.SmsMessage
import dev.adminos.api.domain.ingestion.sms.SmsParserRegistry
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQuery
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

/** AOS-909 on Postgres (V39): pay-later purchases and bills persist, and the bank repayment pays the bill. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PayLaterIntegrationTest {
    private val events = ExposedIngestEventRepository()
    private val txns = ExposedTransactionRepository()
    private val links = ExposedTransactionLinkRepository()
    private val bills = ExposedBillRepository()
    private val billing = BillTrackingService(bills, txns, dev.adminos.api.domain.identity.UserCalendar(dev.adminos.api.domain.identity.InMemoryUserRepository()))

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    @Test
    fun `purchase, bill and repayment SMS end to end`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val intake = SmsIntake(SmsParserRegistry(), IngestEventService(events),
            dev.adminos.api.domain.financial.mandates.MandateService(ExposedMandateRepository(), txns), PayLaterBills(bills, billing), dev.adminos.api.domain.financial.trips.TravelBookings(ExposedTravelBookingRepository()), dev.adminos.api.domain.balances.BalanceService(dev.adminos.api.domain.balances.InMemoryBalanceRepository()))
        val processor = IngestProcessor(events,
            IngestPipeline(listOf(ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()), CategorizeStep(CategorizationService(ExposedCategoryRuleRepository())),
                TransferStep(RepositoryMatchCandidates(txns, links), TransferDetector()))),
            RepositoryOutcomeWriter(txns, events, links) { w -> dbQuery { w() } },
            listeners = listOf(TransactionCreatedListener { if (it.userId == user) BillPaymentListener(billing).onCreated(it) }))
        val now = Instant.now()
        val due = LocalDate.now(BillPaymentMatcher.ZONE).plusDays(6)
        val dueText = "%02d-%s-%d".format(due.dayOfMonth, due.month.name.take(3), due.year)

        intake.accept(user, SmsMessage("VM-LZYPAY", "Rs.640 spent at Blinkit using LazyPay", now.minus(Duration.ofDays(2))))
        intake.accept(user, SmsMessage("JM-LZYPAY-S", "Your LazyPay bill of Rs 640.00 is due on $dueText", now.minus(Duration.ofDays(1))))
        while (processor.processBatch() > 0) {}
        val purchase = txns.findByUserId(user).single()
        assertEquals(PaymentMethod.PAY_LATER, purchase.paymentMethod)
        assertEquals("LazyPay", purchase.metadata?.get("payLater"))
        val bill = bills.findByUserId(user).single()
        assertEquals(BillType.PAY_LATER, bill.billType)

        intake.accept(user, SmsMessage("JM-HDFCBK", "Rs 640.00 debited from A/c XX1234 to VPA lazypay@icici. UPI Ref 323456789012", now))
        while (processor.processBatch() > 0) {}
        assertEquals(BillStatus.PAID, bills.findById(bill.id)?.status)
        val summary = PayLaterService(bills, txns).summary(user, Instant.now())
        assertEquals(0, summary.outstanding.signum(), "the bill is paid and nothing was bought after it")
    }
}
