package dev.adminos.api.domain.financial

import dev.adminos.api.domain.financial.BillPaymentMatcher.CARD_BILL_TRANSFER
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which bill a payment pays (AOS-508): amount, window, evidence, ranking and ambiguity. */
class BillPaymentMatcherTest {
    private val userId = UUID.randomUUID()
    private val due = LocalDate.of(2026, 10, 20)

    private fun bill(name: String = "Tata Power", amount: String = "2350.00", type: BillType = BillType.ELECTRICITY, minimum: String? = null,
                     accountRef: String? = null, dueDate: LocalDate = due, periodEnd: LocalDate? = null, status: BillStatus = BillStatus.UPCOMING,
                     owner: UUID = userId, linked: UUID? = null) =
        Bill(userId = owner, billerName = name, amount = BigDecimal(amount), billType = type, minimumDue = minimum?.let(::BigDecimal),
            accountRef = accountRef, dueDate = dueDate, billingPeriodEnd = periodEnd, status = status, paymentTxnId = linked)

    /** A payment at 12:00 IST on [day]. */
    private fun payment(merchant: String? = "TATA POWER DDL", amount: String = "2350.00", day: LocalDate = due.minusDays(2),
                        nature: TransactionNature = TransactionNature.SPEND, subcategory: String? = null, raw: String? = null,
                        type: TransactionType = TransactionType.DEBIT, at: Instant? = null) =
        Transaction(userId = userId, type = type, nature = nature, amount = BigDecimal(amount), merchantName = merchant, merchantRaw = raw,
            subcategory = subcategory, transactedAt = at ?: day.atTime(12, 0).atZone(BillPaymentMatcher.ZONE).toInstant())

    private fun cardPayment(amount: String, raw: String = "CC PAYMENT BILLDESK", day: LocalDate = due.minusDays(2)) =
        payment(merchant = "Credit card payment", amount = amount, raw = raw, nature = TransactionNature.TRANSFER, subcategory = CARD_BILL_TRANSFER, day = day)

    // ── What can pay a bill ──

    @Test
    fun `spends and credit-card bill transfers can pay bills, other money cannot`() {
        assertTrue(BillPaymentMatcher.canPayBills(payment()))
        assertTrue(BillPaymentMatcher.canPayBills(cardPayment("100")))
        assertFalse(BillPaymentMatcher.canPayBills(payment(nature = TransactionNature.TRANSFER, subcategory = "self_transfer")))
        assertFalse(BillPaymentMatcher.canPayBills(payment(nature = TransactionNature.TRANSFER, subcategory = "cash")))
        assertFalse(BillPaymentMatcher.canPayBills(payment(nature = TransactionNature.REFUND)))
        assertFalse(BillPaymentMatcher.canPayBills(payment(type = TransactionType.CREDIT, nature = TransactionNature.INCOME)))
    }

    // ── Amount ──

    @Test
    fun `the full amount within 5 percent, or the minimum due within 1 percent, covers a bill`() {
        val b = bill(name = "HDFC Credit Card", amount = "45600.00", minimum = "2280.00", type = BillType.CREDIT_CARD)
        assertEquals(PaymentCoverage.FULL, BillPaymentMatcher.coverage(b, BigDecimal("44000.00")))
        assertEquals(PaymentCoverage.MINIMUM_DUE, BillPaymentMatcher.coverage(b, BigDecimal("2280.00")))
        assertNull(BillPaymentMatcher.coverage(b, BigDecimal("10000.00")), "partial, neither full nor minimum")
        assertNull(BillPaymentMatcher.coverage(bill(amount = "0.00"), BigDecimal("0.00")), "a ₹0 bill needs no payment")
    }

    @Test
    fun `the right biller with the wrong amount is not a payment`() {
        assertNull(BillPaymentMatcher.billFor(payment(amount = "1800.00"), listOf(bill())))
    }

    // ── Date window ──

    @Test
    fun `a payment counts from 40 days before the due date to 15 days after`() {
        val b = bill()
        assertEquals(due.minusDays(40)..due.plusDays(15), BillPaymentMatcher.window(b))
        assertEquals(b, BillPaymentMatcher.billFor(payment(day = due.minusDays(40)), listOf(b))?.bill)
        assertEquals(b, BillPaymentMatcher.billFor(payment(day = due.plusDays(15)), listOf(b))?.bill, "late payments count")
        assertNull(BillPaymentMatcher.billFor(payment(day = due.minusDays(41)), listOf(b)), "last month's payment")
        assertNull(BillPaymentMatcher.billFor(payment(day = due.plusDays(16)), listOf(b)))
    }

    @Test
    fun `with a billing period the window starts when it ends`() {
        val b = bill(periodEnd = due.minusDays(18))
        assertEquals(due.minusDays(18)..due.plusDays(15), BillPaymentMatcher.window(b))
        assertNull(BillPaymentMatcher.billFor(payment(day = due.minusDays(19)), listOf(b)), "paid before the bill period closed")
        assertEquals(due.minusDays(40)..due.plusDays(15), BillPaymentMatcher.window(bill(periodEnd = due.plusDays(1))), "a period ending after the due date is ignored")
    }

    @Test
    fun `dates are Indian dates`() {
        // 23:30 IST on the first day of the window is still that day (18:00 UTC).
        val first = due.minusDays(40)
        val lateNight = first.atTime(23, 30).atZone(BillPaymentMatcher.ZONE).toInstant()
        assertEquals(bill().billerName, BillPaymentMatcher.billFor(payment(at = lateNight), listOf(bill()))?.bill?.billerName)
    }

    // ── Evidence ──

    @Test
    fun `the biller's name is evidence`() {
        assertEquals(BillEvidence.BILLER_NAME, BillPaymentMatcher.billFor(payment(), listOf(bill()))?.evidence)
        assertNull(BillPaymentMatcher.billFor(payment(merchant = "Zomato"), listOf(bill())), "right amount, other payee")
        assertNull(BillPaymentMatcher.billFor(payment(merchant = null), listOf(bill())))
    }

    @Test
    fun `the bill's account number in the narration is the strongest evidence, digits matched whole`() {
        val card = bill(name = "HDFC Bank Credit Card", amount = "12000.00", type = BillType.CREDIT_CARD, accountRef = "XXXX XXXX XXXX 4521")
        assertEquals(BillEvidence.ACCOUNT_NUMBER, BillPaymentMatcher.billFor(cardPayment("12000.00", raw = "NEFT CC 4521 PAYMENT"), listOf(card))?.evidence)
        val other = card.copy(id = UUID.randomUUID(), billerName = "ICICI Amazon Pay Card", accountRef = "4522")
        assertEquals(BillEvidence.ONLY_CARD_BILL, BillPaymentMatcher.billFor(cardPayment("12000.00", raw = "PAYMENT 45210"), listOf(card))?.evidence,
            "45210 is not card 4521")
        assertNull(BillPaymentMatcher.billFor(cardPayment("12000.00", raw = "PAYMENT 45210"), listOf(card, other)), "two card bills fit: can't tell")
    }

    @Test
    fun `a card-bill payment to the only card bill that fits is enough, but not for other bills`() {
        val card = bill(name = "SBI Card", amount = "8000.00", type = BillType.CREDIT_CARD)
        assertEquals(BillEvidence.ONLY_CARD_BILL, BillPaymentMatcher.billFor(cardPayment("8000.00", raw = "CRED CLUB"), listOf(card))?.evidence)
        assertNull(BillPaymentMatcher.billFor(cardPayment("8000.00", raw = "CRED CLUB"), listOf(bill(name = "Landlord", amount = "8000.00", type = BillType.RENT))))
        assertNull(BillPaymentMatcher.billFor(payment(merchant = "CRED", amount = "8000.00"), listOf(card)), "an ordinary spend needs the name")
    }

    // ── Which bills ──

    @Test
    fun `paid, cancelled, already-linked and other users' bills are never matched`() {
        listOf(bill(status = BillStatus.PAID), bill(status = BillStatus.CANCELLED), bill(linked = UUID.randomUUID()), bill(owner = UUID.randomUUID()))
            .forEach { assertNull(BillPaymentMatcher.billFor(payment(), listOf(it)), "$it") }
        assertEquals(BillStatus.OVERDUE, BillPaymentMatcher.billFor(payment(day = due.plusDays(3)), listOf(bill(status = BillStatus.OVERDUE)))?.bill?.status)
    }

    @Test
    fun `stronger evidence wins, then the closer amount, then the nearer due date, and a true tie matches nothing`() {
        val byName = bill(name = "HDFC Credit Card", amount = "5000.00", type = BillType.CREDIT_CARD)
        val byNumber = bill(name = "My card", amount = "5000.00", type = BillType.CREDIT_CARD, accountRef = "7788")
        assertEquals(byNumber, BillPaymentMatcher.billFor(cardPayment("5000.00", raw = "HDFC CREDIT CARD 7788"), listOf(byName, byNumber))?.bill)

        val exact = bill(amount = "2350.00")
        val near = bill(amount = "2400.00")
        assertEquals(exact, BillPaymentMatcher.billFor(payment(), listOf(near, exact))?.bill)

        val sooner = bill(dueDate = due)
        val later = bill(dueDate = due.plusDays(10))
        assertEquals(sooner, BillPaymentMatcher.billFor(payment(), listOf(later, sooner))?.bill)

        assertNull(BillPaymentMatcher.billFor(payment(), listOf(bill(), bill())), "two identical bills: ambiguous")
    }

    // ── Sweep direction: a payment for a bill ──

    @Test
    fun `the sweep finds the payment for a bill, and refuses to guess between two`() {
        val b = bill()
        val pay = payment()
        assertEquals(pay, BillPaymentMatcher.paymentFor(b, listOf(payment(merchant = "Zomato"), pay, payment(nature = TransactionNature.REFUND))))
        assertNull(BillPaymentMatcher.paymentFor(b, listOf(pay, payment())), "two equal payments")
        assertEquals(pay, BillPaymentMatcher.paymentFor(b, listOf(pay, payment(amount = "2300.00"))), "the closer amount")
        assertNull(BillPaymentMatcher.paymentFor(bill(status = BillStatus.PAID), listOf(pay)))
        assertNull(BillPaymentMatcher.paymentFor(bill(linked = UUID.randomUUID()), listOf(pay)))
        assertNull(BillPaymentMatcher.paymentFor(b, emptyList()))
    }

    // ── Service: marking paid ──

    private val bills = InMemoryBillRepository()
    private val transactions = InMemoryTransactionRepository()
    private val billing = BillTrackingService(bills, transactions, dev.adminos.api.domain.identity.UserCalendar(dev.adminos.api.domain.identity.InMemoryUserRepository()))

    @Test
    fun `a payment marks its bill paid, records how it matched, and only once`() = runBlocking<Unit> {
        val b = bills.save(bill(name = "HDFC Credit Card", amount = "45600.00", minimum = "2280.00", type = BillType.CREDIT_CARD))
        val pay = cardPayment("2280.00", raw = "HDFC CREDIT CARD BILLDESK")
        val paid = billing.matchPayment(pay)!!
        assertEquals(BillStatus.PAID, paid.status)
        assertEquals(pay.id, paid.paymentTxnId)
        assertEquals(0, BigDecimal("2280.00").compareTo(paid.paidAmount))
        assertEquals(mapOf("paidCoverage" to "minimum_due", "matchedBy" to "biller_name"), paid.metadata)
        assertEquals(paid, bills.findById(b.id))
        assertNull(billing.matchPayment(pay), "already paid")
        assertNull(billing.matchPayment(payment(nature = TransactionNature.INCOME, type = TransactionType.CREDIT)))
    }

    @Test
    fun `the sweep pays bills whose payment came first, and leaves the rest`() = runBlocking<Unit> {
        transactions.save(payment())
        val paidFirst = bills.save(bill())
        val unpaid = bills.save(bill(name = "BESCOM", amount = "1800.00"))
        assertEquals(1, billing.matchEarlierPayments(listOf(paidFirst, unpaid)))
        assertEquals(BillStatus.PAID, bills.findById(paidFirst.id)!!.status)
        assertEquals("sweep", bills.findById(paidFirst.id)!!.metadata?.get("matchedBy"))
        assertEquals(BillStatus.UPCOMING, bills.findById(unpaid.id)!!.status)
    }

    @Test
    fun `the listener matches each new transaction`() = runBlocking<Unit> {
        val b = bills.save(bill())
        BillPaymentListener(billing).onCreated(payment())
        assertEquals(BillStatus.PAID, bills.findById(b.id)!!.status)
    }
}
