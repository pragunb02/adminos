package dev.adminos.api.domain.financial

import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The dues calendar (AOS-503): bills by due date, renewals projected, overdue carried, totals. */
class DuesServiceTest {
    private val userId = UUID.randomUUID()
    private val bills = InMemoryBillRepository()
    private val subscriptions = InMemorySubscriptionRepository()
    private val mandateRepo = dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository()
    private val dues = DuesService(bills, subscriptions, dev.adminos.api.domain.financial.mandates.MandateService(mandateRepo, InMemoryTransactionRepository()))
    private val today = LocalDate.of(2026, 10, 16)
    private val october = YearMonth.of(2026, 10)

    private suspend fun bill(name: String, due: LocalDate, amount: String = "1000.00", status: BillStatus = BillStatus.UPCOMING, paid: String? = null, owner: UUID = userId) =
        bills.save(Bill(userId = owner, billerName = name, amount = BigDecimal(amount), dueDate = due, status = status, paidAmount = paid?.let(::BigDecimal),
            billType = BillType.ELECTRICITY, minimumDue = null))

    private fun sub(next: LocalDate?, cycle: BillingCycle = BillingCycle.MONTHLY, status: SubscriptionStatus = SubscriptionStatus.ACTIVE, name: String = "Netflix") =
        Subscription(userId = userId, name = name, amount = BigDecimal("649.00"), billingCycle = cycle, nextBillingDate = next, status = status)

    @Test
    fun `a month lists bills and renewals by date, bills first on a shared day`() = runBlocking<Unit> {
        bill("Tata Power", LocalDate.of(2026, 10, 20))
        bill("Airtel", LocalDate.of(2026, 10, 25), status = BillStatus.PAID, amount = "599.00", paid = "599.00")
        bill("Cancelled", LocalDate.of(2026, 10, 21), status = BillStatus.CANCELLED)
        bill("November", LocalDate.of(2026, 11, 2))
        bill("Someone else's", LocalDate.of(2026, 10, 20), owner = UUID.randomUUID())
        subscriptions.save(sub(LocalDate.of(2026, 10, 20)))

        val m = dues.month(userId, october, today)
        assertEquals(listOf("Tata Power" to DueKind.BILL, "Netflix" to DueKind.RENEWAL, "Airtel" to DueKind.BILL), m.items.map { it.name to it.kind })
        assertEquals("expected", m.items[1].status)
        assertEquals("monthly", m.items[1].detail)
        assertEquals("paid", m.items[2].status)
        assertEquals(DueTotals(open = BigDecimal("1000.00"), paid = BigDecimal("599.00"), renewals = BigDecimal("649.00")), m.totals)
    }

    @Test
    fun `overdue bills from earlier months come with this month only`() = runBlocking<Unit> {
        bill("BESCOM", LocalDate.of(2026, 9, 28), status = BillStatus.OVERDUE, amount = "1800.00")
        bill("This month overdue", LocalDate.of(2026, 10, 2), status = BillStatus.OVERDUE, amount = "100.00")
        val now = dues.month(userId, october, today)
        assertEquals(listOf("BESCOM"), now.overdue.map { it.name })
        assertEquals(BigDecimal("1900.00"), now.totals.open)
        assertTrue(dues.month(userId, YearMonth.of(2026, 11), today).overdue.isEmpty())
    }

    @Test
    fun `renewals are projected by cycle from today on, at most a year ahead`() {
        val oct = october.atDay(1) to october.atEndOfMonth()
        fun dates(s: Subscription, m: YearMonth = october, t: LocalDate = today) = DuesService.renewalDates(s, m.atDay(1), m.atEndOfMonth(), t)
        assertEquals(listOf(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26)), dates(sub(LocalDate.of(2026, 10, 12), BillingCycle.WEEKLY)), "past weeks are already charges")
        assertEquals(listOf(LocalDate.of(2027, 1, 10)), dates(sub(LocalDate.of(2026, 10, 10), BillingCycle.QUARTERLY), YearMonth.of(2027, 1)))
        assertEquals(listOf(LocalDate.of(2027, 10, 16)), dates(sub(LocalDate.of(2026, 10, 16), BillingCycle.YEARLY), YearMonth.of(2027, 10)))
        assertEquals(emptyList(), dates(sub(LocalDate.of(2026, 10, 16), BillingCycle.YEARLY), YearMonth.of(2028, 10)), "beyond a year")
        assertEquals(listOf(LocalDate.of(2026, 10, 17)), dates(sub(LocalDate.of(2026, 10, 17), BillingCycle.DAILY)), "daily: only the next one")
        assertEquals(emptyList(), dates(sub(null)))
        assertEquals(oct.first, october.atDay(1))
    }

    @Test
    fun `a renewal on the 31st stays on month ends instead of drifting`() {
        val s = sub(LocalDate.of(2026, 10, 31))
        val feb = YearMonth.of(2027, 2)
        val mar = YearMonth.of(2027, 3)
        assertEquals(listOf(LocalDate.of(2027, 2, 28)), DuesService.renewalDates(s, feb.atDay(1), feb.atEndOfMonth(), today))
        assertEquals(listOf(LocalDate.of(2027, 3, 31)), DuesService.renewalDates(s, mar.atDay(1), mar.atEndOfMonth(), today))
    }

    @Test
    fun `paused and cancelled subscriptions aren't shown, trials are, marked as trials`() = runBlocking<Unit> {
        subscriptions.save(sub(LocalDate.of(2026, 10, 20), status = SubscriptionStatus.CANCELLED, name = "Old"))
        subscriptions.save(sub(LocalDate.of(2026, 10, 20), status = SubscriptionStatus.PAUSED, name = "Paused"))
        subscriptions.save(sub(LocalDate.of(2026, 10, 22), status = SubscriptionStatus.TRIAL, name = "Trial"))
        val items = dues.month(userId, october, today).items
        assertEquals(listOf("Trial"), items.map { it.name })
        assertTrue(items.single().trial)
    }

    @Test
    fun `an empty month has zero totals`() = runBlocking<Unit> {
        val m = dues.month(userId, october, today)
        assertTrue(m.items.isEmpty())
        assertEquals(DueTotals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), m.totals)
    }
}
