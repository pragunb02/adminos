package dev.adminos.api.domain.financial.paylater

import dev.adminos.api.domain.financial.*
import dev.adminos.api.domain.financial.links.InMemoryTransactionLinkRepository
import dev.adminos.api.domain.financial.links.TransferDetector
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.pipeline.*
import dev.adminos.api.domain.ingestion.sms.PayLaterBillNotice
import dev.adminos.api.domain.ingestion.sms.SmsIntake
import dev.adminos.api.domain.ingestion.sms.SmsIntakeResult
import dev.adminos.api.domain.ingestion.sms.SmsMessage
import dev.adminos.api.domain.ingestion.sms.SmsParserRegistry
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-909 end to end in memory: provider SMS → dues, tagged purchases, repayments as transfers that pay the bill. */
class PayLaterFlowTest {
    private val user = UUID.randomUUID()
    private val events = InMemoryIngestEventRepository()
    private val txns = InMemoryTransactionRepository()
    private val links = InMemoryTransactionLinkRepository()
    private val bills = InMemoryBillRepository()
    private val billing = BillTrackingService(bills, txns, dev.adminos.api.domain.identity.UserCalendar(dev.adminos.api.domain.identity.InMemoryUserRepository()))
    private val payLaterBills = PayLaterBills(bills, billing)
    private val intake = SmsIntake(SmsParserRegistry(), IngestEventService(events),
        dev.adminos.api.domain.financial.mandates.MandateService(dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository(), txns), payLaterBills, dev.adminos.api.domain.financial.trips.TravelBookings(dev.adminos.api.domain.financial.trips.InMemoryTravelBookingRepository()))
    private val processor = IngestProcessor(events,
        IngestPipeline(listOf(ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()), CategorizeStep(CategorizationService(InMemoryCategoryRuleRepository())),
            TransferStep(RepositoryMatchCandidates(txns, links), TransferDetector()))),
        RepositoryOutcomeWriter(txns, events, links), listeners = listOf(BillPaymentListener(billing)))
    private val now = Instant.now()
    private val today = LocalDate.now(BillPaymentMatcher.ZONE)

    private suspend fun sms(sender: String, text: String, at: Instant = now): SmsIntakeResult {
        val r = intake.accept(user, SmsMessage(sender, text, at))
        while (processor.processBatch() > 0) {}
        return r
    }

    private fun dueText(days: Long) = today.plusDays(days).let { "%02d-%s-%d".format(it.dayOfMonth, it.month.name.take(3).lowercase().replaceFirstChar(Char::uppercase), it.year) }

    @Test
    fun `a provider's bill SMS becomes a pay-later bill in the dues calendar, once`() = runBlocking<Unit> {
        assertEquals(SmsIntakeResult.Bill, sms("JM-LZYPAY-S", "Your LazyPay bill of Rs 1,234.00 is due on ${dueText(10)}."))
        assertEquals(SmsIntakeResult.Bill, sms("JM-LZYPAY-S", "Reminder: LazyPay total due Rs 1,234.00, min due Rs 100, due on ${dueText(10)}."))
        val bill = bills.findByUserId(user).single()
        assertEquals(BillType.PAY_LATER, bill.billType)
        assertEquals("LazyPay", bill.billerName)
        assertEquals(BigDecimal("100.00"), bill.minimumDue, "a reminder fills in the minimum due")
        assertEquals("sms", bill.detectionSource)
        val dues = DuesService(bills, InMemorySubscriptionRepository(), dev.adminos.api.domain.financial.mandates.MandateService(
            dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository(), txns)).month(user, java.time.YearMonth.from(bill.dueDate), today)
        assertEquals("pay_later", dues.items.single().detail)
    }

    @Test
    fun `the same bill by email, a day apart with the same amount, is the same bill`() = runBlocking<Unit> {
        val first = payLaterBills.record(user, PayLaterBillNotice("Simpl", BigDecimal("500.00"), null, today.plusDays(5)), now, "a", "sms")
        val second = payLaterBills.record(user, PayLaterBillNotice("simpl", BigDecimal("500.00"), null, today.plusDays(6)), now, "b", "gmail")
        assertIs<PayLaterBillOutcome.Created>(first)
        assertIs<PayLaterBillOutcome.Known>(second)
        val other = payLaterBills.record(user, PayLaterBillNotice("Simpl", BigDecimal("700.00"), null, today.plusDays(6)), now, "c", "sms")
        assertIs<PayLaterBillOutcome.Created>(other, "another amount on another date is another bill")
    }

    @Test
    fun `a purchase is a spend at the merchant, tagged pay-later, and its bank repayment is a transfer that pays the bill`() = runBlocking<Unit> {
        assertEquals(SmsIntakeResult.Accepted, sms("VM-LZYPAY", "Rs.450 spent at Swiggy using LazyPay", now.minus(Duration.ofDays(3))))
        val purchase = txns.findByUserId(user).single()
        assertEquals(PaymentMethod.PAY_LATER, purchase.paymentMethod)
        assertEquals("LazyPay", purchase.metadata?.get("payLater"))
        assertEquals(TransactionNature.SPEND, purchase.nature)
        assertEquals(TransactionCategory.FOOD, purchase.category)

        sms("JM-LZYPAY-S", "Your LazyPay bill of Rs 450.00 is due on ${dueText(5)}.")
        sms("JM-HDFCBK", "Rs 450.00 debited from A/c XX1234 to VPA lazypay@icici on ${today}. UPI Ref 123456789012")
        val repayment = txns.findByUserId(user).first { it.id != purchase.id }
        assertEquals(TransactionNature.TRANSFER, repayment.nature, "the purchase was the spending")
        assertEquals(BillPaymentMatcher.PAY_LATER_BILL_TRANSFER, repayment.subcategory)
        val bill = bills.findByUserId(user).single()
        assertEquals(BillStatus.PAID, bill.status)
        assertEquals(repayment.id, bill.paymentTxnId)
    }

    @Test
    fun `the pay later lens counts the purchase once and never its repayment`() = runBlocking<Unit> {
        sms("VM-LZYPAY", "Rs.450 spent at Swiggy using LazyPay", now.minus(Duration.ofDays(3)))
        sms("JM-HDFCBK", "Rs 450.00 debited from A/c XX1234 to VPA lazypay@icici on ${today}. UPI Ref 123456789012")
        val matcher = dev.adminos.api.domain.coach.lenses.LensMatcher()
        val none = dev.adminos.api.domain.coach.lenses.LensOverrides.NONE
        val payLater = dev.adminos.api.domain.coach.lenses.LensId("pay_later")
        val (purchase, repayment) = txns.findByUserId(user).partition { it.paymentMethod == PaymentMethod.PAY_LATER }
        assertTrue(payLater in matcher.lensesOf(purchase.single(), none))
        assertEquals(emptySet(), matcher.lensesOf(repayment.single(), none))
        assertEquals(1, txns.findByUserId(user).count { payLater in matcher.lensesOf(it, none) })
    }

    @Test
    fun `a repayment recorded before the bill arrives pays it at once`()= runBlocking<Unit> {
        sms("JM-HDFCBK", "Rs 999.00 debited from A/c XX1234 to VPA getsimpl@axis on ${today}. UPI Ref 223456789012")
        sms("VM-SIMPL", "Your Simpl bill of Rs 999 is due on ${dueText(3)}.")
        assertEquals(BillStatus.PAID, bills.findByUserId(user).single().status)
    }

    @Test
    fun `debt outstanding is unpaid bills plus purchases since the latest bill, and gaps are shown`() = runBlocking<Unit> {
        val service = PayLaterService(bills, txns)
        fun purchase(amount: String, daysAgo: Long, provider: String?) = Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal(amount),
            merchantName = "Zepto", paymentMethod = PaymentMethod.PAY_LATER, transactedAt = now.minus(Duration.ofDays(daysAgo)),
            metadata = provider?.let { mapOf("payLater" to it) })
        txns.save(purchase("200.00", 20, "LazyPay"))
        bills.save(Bill(userId = user, billType = BillType.PAY_LATER, billerName = "LazyPay", amount = BigDecimal("1200.00"), dueDate = today.plusDays(4),
            createdAt = now.minus(Duration.ofDays(10))))
        txns.save(purchase("150.00", 2, "lazypay"))
        txns.save(purchase("80.00", 1, null))
        txns.save(purchase("999.00", 1, "LazyPay").copy(userId = UUID.randomUUID()))
        bills.save(Bill(userId = user, billType = BillType.CREDIT_CARD, billerName = "HDFC", amount = BigDecimal("5000.00"), dueDate = today.plusDays(4)))

        val s = service.summary(user, now)
        val lazy = s.providers.first { it.provider == "LazyPay" }
        assertEquals(BigDecimal("1350.00"), lazy.outstanding)
        assertEquals(1, lazy.unbilledPurchases.size)
        assertEquals(today.plusDays(4), lazy.nextDue)
        assertEquals(BigDecimal("350.00"), lazy.purchasesLast30Days)
        val gap = lazy.gaps.single()
        assertEquals(BigDecimal("200.00"), gap.recorded)
        assertEquals(BigDecimal("1000.00"), gap.unrecorded, "₹1,200 billed, only ₹200 of purchases recorded")
        assertEquals(BigDecimal("80.00"), s.providers.first { it.provider == PayLaterService.UNKNOWN_PROVIDER }.outstanding)
        assertEquals(BigDecimal("1430.00"), s.outstanding)
        assertTrue(s.providers.none { it.provider == "HDFC" })
    }

    @Test
    fun `a paid bill fully covered by purchases has no gap, and an empty history has no providers`() = runBlocking<Unit> {
        val service = PayLaterService(bills, txns)
        assertTrue(service.summary(user, now).providers.isEmpty())
        txns.save(Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal("300.00"), paymentMethod = PaymentMethod.PAY_LATER,
            transactedAt = now.minus(Duration.ofDays(40)), metadata = mapOf("payLater" to "Simpl")))
        bills.save(Bill(userId = user, billType = BillType.PAY_LATER, billerName = "Simpl", amount = BigDecimal("300.00"), dueDate = today.minusDays(20),
            status = BillStatus.PAID, createdAt = now.minus(Duration.ofDays(35))))
        assertTrue(service.summary(user, now).providers.isEmpty(), "nothing owed, nothing recent, no gap")
    }

    @Test
    fun `providers are found by name, and a pay-later purchase is never a repayment`() {
        assertEquals("Simpl", PayLaterProviders.byName(" simpl ")?.name)
        assertNull(PayLaterProviders.byName("Nobody"))
        val d = TransferDetector()
        val t = Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal.TEN, merchantRaw = "LAZYPAY", transactedAt = now)
        assertTrue(d.isPayLaterRepayment(t))
        assertTrue(!d.isPayLaterRepayment(t.copy(paymentMethod = PaymentMethod.PAY_LATER)))
        assertTrue(!d.isPayLaterRepayment(t.copy(type = TransactionType.CREDIT)))
        assertTrue(!d.isPayLaterRepayment(t.copy(merchantRaw = "SIMPLY FRESH")), "whole words only")
        assertEquals("Amazon Pay Later", PayLaterProviders.paidTo(t.copy(merchantRaw = null, upiVpa = "amazonpaylater@apl"))?.name)
    }
}
