package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.financial.Bill
import dev.adminos.api.domain.financial.BillStatus
import dev.adminos.api.domain.financial.BillType
import dev.adminos.api.domain.financial.BillingCycle
import dev.adminos.api.domain.financial.DueItem
import dev.adminos.api.domain.financial.DueKind
import dev.adminos.api.domain.financial.DuesService
import dev.adminos.api.domain.financial.InMemoryBillRepository
import dev.adminos.api.domain.financial.InMemorySubscriptionRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.Subscription
import dev.adminos.api.domain.financial.SubscriptionStatus
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository
import dev.adminos.api.domain.financial.mandates.Mandate
import dev.adminos.api.domain.financial.mandates.MandateFrequency
import dev.adminos.api.domain.financial.mandates.MandateRail
import dev.adminos.api.domain.financial.mandates.MandateService
import dev.adminos.api.domain.forecast.ForecastService.Companion.toKnownDue
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-714: the service loads history, dues and income on the user's own calendar and keeps recurring money out of the rate. */
class ForecastServiceTest {
    private val ist: ZoneId = ZoneId.of("Asia/Kolkata")
    private val user = UUID.randomUUID()
    private val transactions = InMemoryTransactionRepository()
    private val bills = InMemoryBillRepository()
    private val subscriptions = InMemorySubscriptionRepository()
    private val mandates = InMemoryMandateRepository()
    private val salaryChoices = InMemorySalaryChoiceRepository()
    private val dues = DuesService(bills, subscriptions, MandateService(mandates, transactions))
    /** 20 Oct 2026, 10:00 in India. */
    private val clock = Clock.fixed(Instant.parse("2026-10-20T04:30:00Z"), ZoneOffset.UTC)
    private val service = ForecastService(InMemoryForecastHistoryRepository(transactions, bills), dues, subscriptions, bills, salaryChoices, clock = clock)

    private fun d(s: String) = LocalDate.parse(s)
    private fun at(day: LocalDate, time: String = "12:00") = day.atTime(LocalTime.parse(time)).atZone(ist).toInstant()

    private suspend fun spend(day: LocalDate, amount: String, merchant: String = "Chai Point", recurring: Boolean = false, type: TransactionType = TransactionType.DEBIT) =
        transactions.save(Transaction(userId = user, type = type, amount = BigDecimal(amount), merchantName = merchant, isRecurring = recurring, transactedAt = at(day)))

    /** 300 every day from 1 Jun 2026 to today, and a salary of 85,000 on each 1st. */
    private suspend fun everydayLife() {
        var day = d("2026-06-01")
        while (!day.isAfter(d("2026-10-20"))) {
            spend(day, "300")
            if (day.dayOfMonth == 1) transactions.save(Transaction(userId = user, type = TransactionType.CREDIT, amount = BigDecimal("85000"), merchantName = "ACME TECHNOLOGIES", transactedAt = at(day, "09:00")))
            day = day.plusDays(1)
        }
    }

    @Test
    fun `a new user with no transactions gets not enough history`() = runBlocking<Unit> {
        val f = assertIs<Forecast.NotEnoughHistory>(service.forecast(user, ist))
        assertEquals(0, f.historyDays)
        assertNull(f.readyOn)
        assertEquals(d("2026-10-20"), f.today)
    }

    @Test
    fun `a full forecast with history, dues and salary`() = runBlocking<Unit> {
        everydayLife()
        bills.save(Bill(userId = user, billerName = "Tata Power", billType = BillType.ELECTRICITY, amount = BigDecimal("2350.50"), dueDate = d("2026-10-25")))
        bills.save(Bill(userId = user, billerName = "HDFC Credit Card", billType = BillType.CREDIT_CARD, amount = BigDecimal("40000"), dueDate = d("2026-10-27")))
        val f = assertIs<Forecast.Ready>(service.forecast(user, ist))
        val m = f.monthEnd
        assertEquals(d("2026-10-20"), m.today)
        assertEquals(BigDecimal("6000.00"), m.spentSoFar)
        val bills = m.parts.filterIsInstance<ComponentEstimate.Dues>().single { it.source == DueSource.BILL }
        assertEquals(listOf("Tata Power"), bills.items.map { it.name }, "a card bill is paid by transfer, so it isn't more spending")
        assertEquals(BigDecimal("3300.00"), m.parts.filterIsInstance<ComponentEstimate.RunRate>().single().amount)
        val salary = assertIs<SalaryOutlook.Expected>(f.salary).untilSalary.salary
        assertEquals(d("2026-11-01"), salary.expectedOn)
        assertEquals("ACME TECHNOLOGIES", salary.payer)
    }

    @Test
    fun `subscription, mandate and bill payments are recurring, not everyday spending`() = runBlocking<Unit> {
        everydayLife()
        subscriptions.save(Subscription(userId = user, name = "Netflix", merchantName = "netflix", amount = BigDecimal("649"), billingCycle = BillingCycle.MONTHLY, nextBillingDate = d("2026-10-28")))
        subscriptions.save(Subscription(userId = user, name = "Old Gym", merchantName = "old gym", amount = BigDecimal("999"), billingCycle = BillingCycle.MONTHLY, status = SubscriptionStatus.CANCELLED))
        var day = d("2026-06-28")
        while (day.isBefore(d("2026-10-20"))) {
            spend(day, "649", merchant = "Netflix")
            spend(day.plusDays(1), "5000", merchant = "Zerodha SIP", recurring = true)
            day = day.plusMonths(1)
        }
        val paid = spend(d("2026-10-05"), "18000", merchant = "Landlord")
        bills.save(Bill(userId = user, billerName = "Rent", billType = BillType.RENT, amount = BigDecimal("18000"), dueDate = d("2026-10-05"), status = BillStatus.PAID, paymentTxnId = paid.id))
        val m = assertIs<Forecast.Ready>(service.forecast(user, ist)).monthEnd
        val everyday = m.parts.filterIsInstance<ComponentEstimate.RunRate>().single()
        assertEquals(BigDecimal("300.00"), everyday.fit.rateOf(DayType.WEEKDAY), "only the 300 a day is everyday spending")
        assertEquals(BigDecimal("649"), m.parts.filterIsInstance<ComponentEstimate.Dues>().single { it.source == DueSource.RENEWAL }.amount)
        // spent so far still counts everything (D-46): 20 × 300 + rent.
        assertEquals(BigDecimal("24000.00"), m.spentSoFar)
    }

    @Test
    fun `only future-dated transactions are not history yet`() = runBlocking<Unit> {
        spend(d("2026-10-25"), "100")
        assertIs<Forecast.NotEnoughHistory>(service.forecast(user, ist))
    }

    @Test
    fun `a trial subscription's charges are recurring too, and unnamed spending stays everyday`() = runBlocking<Unit> {
        everydayLife()
        subscriptions.save(Subscription(userId = user, name = "Cult Fit", merchantName = " ", amount = BigDecimal("1"), billingCycle = BillingCycle.MONTHLY, status = SubscriptionStatus.TRIAL))
        spend(d("2026-10-02"), "99", merchant = "CULT FIT")
        transactions.save(Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal("50"), merchantName = null, transactedAt = at(d("2026-10-03"))))
        val m = assertIs<Forecast.Ready>(service.forecast(user, ist)).monthEnd
        assertEquals(BigDecimal("6149.00"), m.spentSoFar)
        assertEquals(BigDecimal("300.00"), m.parts.filterIsInstance<ComponentEstimate.RunRate>().single().fit.rateOf(DayType.WEEKDAY))
    }

    @Test
    fun `refunds reduce the day's spending`() = runBlocking<Unit> {
        everydayLife()
        spend(d("2026-10-10"), "300", type = TransactionType.REFUND)
        val m = assertIs<Forecast.Ready>(service.forecast(user, ist)).monthEnd
        assertEquals(BigDecimal("5700.00"), m.spentSoFar)
    }

    @Test
    fun `days are the user's own, not the server's`() = runBlocking<Unit> {
        everydayLife()
        // 23:30 UTC on 19 Oct is 05:00 on 20 Oct in India: it counts today, in October.
        transactions.save(Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal("1000"), merchantName = "Late night", transactedAt = Instant.parse("2026-10-19T23:30:00Z")))
        assertEquals(BigDecimal("7000.00"), assertIs<Forecast.Ready>(service.forecast(user, ist)).monthEnd.spentSoFar)
        // In Tokyo, the clock's instant is already 20 Oct 13:30; the month started on Tokyo's 1 Oct.
        assertEquals(d("2026-10-20"), (service.forecast(user, ZoneId.of("Asia/Tokyo")) as Forecast.Ready).monthEnd.today)
    }

    @Test
    fun `transfers count as activity but not as spending`() = runBlocking<Unit> {
        transactions.save(Transaction(userId = user, type = TransactionType.TRANSFER, amount = BigDecimal("5000"), transactedAt = at(d("2026-06-01"))))
        spend(d("2026-10-01"), "100")
        val m = assertIs<Forecast.Ready>(service.forecast(user, ist)).monthEnd
        assertEquals(BigDecimal("100.00"), m.spentSoFar)
    }

    @Test
    fun `a mandate debit after today is a known due`() = runBlocking<Unit> {
        everydayLife()
        mandates.save(Mandate(userId = user, rail = MandateRail.UPI_AUTOPAY, payee = "LIC", amount = BigDecimal("2500"), frequency = MandateFrequency.MONTHLY,
            nextDebitOn = d("2026-10-26"), statusChangedAt = Instant.parse("2026-09-01T00:00:00Z")))
        val m = assertIs<Forecast.Ready>(service.forecast(user, ist)).monthEnd
        assertEquals(listOf("LIC"), m.parts.filterIsInstance<ComponentEstimate.Dues>().single { it.source == DueSource.MANDATE }.items.map { it.name })
    }

    // ── Projected monthly bills (AOS-721) ──

    /** A paid electricity bill of [amount] due on the 25th of each of [months] (YYYY-MM), for [owner]. */
    private suspend fun monthlyBills(months: List<String>, amount: String = "1200", owner: UUID = user, biller: String = "Sample Power") = months.forEach {
        bills.save(Bill(userId = owner, billerName = biller, billType = BillType.ELECTRICITY, amount = BigDecimal(amount), dueDate = d("$it-25"), status = BillStatus.PAID))
    }

    private fun projectedOf(m: MonthEndForecast) = m.parts.filterIsInstance<ProjectedBillsEstimate>().single()

    @Test
    fun `a regular bill that hasn't arrived this month is projected from the user's own bills`() = runBlocking<Unit> {
        everydayLife()
        monthlyBills(listOf("2026-07", "2026-08", "2026-09"))
        val item = projectedOf(assertIs<Forecast.Ready>(service.forecast(user, ist)).monthEnd).items.single()
        assertEquals("Sample Power", item.name)
        assertEquals(d("2026-10-25"), item.expectedOn)
        assertEquals(BigDecimal("1200.00"), item.amount)
    }

    @Test
    fun `another user's bills are never projected into my forecast`() = runBlocking<Unit> {
        everydayLife()
        monthlyBills(listOf("2026-07", "2026-08", "2026-09"), owner = UUID.randomUUID(), biller = "Their Power")
        assertTrue(projectedOf(assertIs<Forecast.Ready>(service.forecast(user, ist)).monthEnd).items.isEmpty())
    }

    @Test
    fun `once this month's bill arrives it is a known due and is not projected again`() = runBlocking<Unit> {
        everydayLife()
        monthlyBills(listOf("2026-07", "2026-08", "2026-09"))
        bills.save(Bill(userId = user, billerName = "Sample Power", billType = BillType.ELECTRICITY, amount = BigDecimal("1300"), dueDate = d("2026-10-25")))
        val m = assertIs<Forecast.Ready>(service.forecast(user, ist)).monthEnd
        assertEquals(listOf("Sample Power"), m.parts.filterIsInstance<ComponentEstimate.Dues>().single { it.source == DueSource.BILL }.items.map { it.name })
        assertTrue(projectedOf(m).items.isEmpty())
        // 6,000 spent + the 1,300 bill once + 11 days × 300.
        assertEquals(BigDecimal("10600.00"), m.total)
    }

    @Test
    fun `too many bills to load whole projects nothing rather than double an arrived bill`() = runBlocking<Unit> {
        everydayLife()
        monthlyBills(listOf("2026-07", "2026-08", "2026-09"))
        repeat(ForecastService.MAX_BILLS) { bills.save(Bill(userId = user, billerName = "Biller $it", billType = BillType.OTHER, amount = BigDecimal("10"), dueDate = d("2026-05-01"))) }
        assertTrue(projectedOf(assertIs<Forecast.Ready>(service.forecast(user, ist)).monthEnd).items.isEmpty())
    }

    // ── The owner's salary choice (AOS-722) ──

    private fun choice(day: Int) = SalaryChoice("ACME TECHNOLOGIES", day, BigDecimal("85000.00"), SalaryChoiceKind.CORRECTED, Instant.parse("2026-10-18T05:00:00Z"))

    @Test
    fun `the user's stored salary choice drives the salary outlook`() = runBlocking<Unit> {
        everydayLife()
        salaryChoices.put(user, choice(5))
        val s = assertIs<SalaryOutlook.Expected>(assertIs<Forecast.Ready>(service.forecast(user, ist)).salary).untilSalary.salary
        assertEquals(SalarySource.CONFIRMED, s.source)
        assertEquals(d("2026-11-05"), s.expectedOn)
    }

    @Test
    fun `another user's salary choice is never used for mine`() = runBlocking<Unit> {
        everydayLife()
        salaryChoices.put(UUID.randomUUID(), choice(5))
        val s = assertIs<SalaryOutlook.Expected>(assertIs<Forecast.Ready>(service.forecast(user, ist)).salary).untilSalary.salary
        assertEquals(SalarySource.DETECTED, s.source)
        assertEquals(d("2026-11-01"), s.expectedOn)
    }

    // ── Dues-calendar items → known payments ──

    private val today = d("2026-10-20")
    private fun item(kind: DueKind, date: String, status: String = "upcoming", detail: String = "electricity", amount: String = "100", name: String = "X") =
        DueItem(kind, UUID.randomUUID(), name, BigDecimal(amount), date = d(date), status = status, detail = detail)

    @Test
    fun `open bills are known dues and an overdue one is expected tomorrow`() {
        assertEquals(KnownDue(DueSource.BILL, "X", d("2026-10-25"), BigDecimal("100")), item(DueKind.BILL, "2026-10-25").toKnownDue(today))
        val overdue = item(DueKind.BILL, "2026-09-28", status = "overdue").toKnownDue(today)
        assertEquals(d("2026-10-21"), overdue?.date)
        assertEquals(true, overdue?.overdue)
        val dueToday = item(DueKind.BILL, "2026-10-20", status = "due").toKnownDue(today)
        assertEquals(d("2026-10-21"), dueToday?.date)
        assertEquals(false, dueToday?.overdue)
    }

    @Test
    fun `paid, cancelled, card and empty bills are not known dues`() {
        assertNull(item(DueKind.BILL, "2026-10-25", status = "paid").toKnownDue(today))
        assertNull(item(DueKind.BILL, "2026-10-25", status = "cancelled").toKnownDue(today))
        assertNull(item(DueKind.BILL, "2026-10-25", detail = "credit_card").toKnownDue(today))
        assertNull(item(DueKind.BILL, "2026-10-25", amount = "0").toKnownDue(today))
    }

    @Test
    fun `renewals and mandates count from tomorrow, and a blank name gets a label`() {
        assertNull(item(DueKind.RENEWAL, "2026-10-20", status = "expected").toKnownDue(today))
        assertNull(item(DueKind.MANDATE, "2026-10-20", status = "expected").toKnownDue(today))
        assertNull(item(DueKind.MANDATE, "2026-10-22", status = "expected", amount = "0").toKnownDue(today))
        assertEquals(DueSource.RENEWAL, item(DueKind.RENEWAL, "2026-10-21", status = "expected").toKnownDue(today)?.source)
        assertEquals("Mandate", item(DueKind.MANDATE, "2026-10-21", status = "expected", name = " ").toKnownDue(today)?.name)
    }

    @Test
    fun `income credits keep the payer and spending rows group by merchant and recurring`() = runBlocking<Unit> {
        val repo = InMemoryForecastHistoryRepository(transactions, bills)
        spend(d("2026-10-01"), "100", merchant = "A")
        spend(d("2026-10-01"), "50", merchant = "A")
        spend(d("2026-10-01"), "40", merchant = "A", recurring = true)
        transactions.save(Transaction(userId = user, type = TransactionType.CREDIT, amount = BigDecimal("500"), nature = TransactionNature.INCOME, merchantName = "Payer", transactedAt = at(d("2026-10-02"))))
        val range = dev.adminos.api.domain.analytics.DateRange(d("2026-10-01"), d("2026-10-31"))
        val rows = repo.spending(user, range, ist).sortedBy { it.recurring }
        assertEquals(listOf(BigDecimal("150"), BigDecimal("40")), rows.map { it.amount })
        assertEquals(listOf(IncomeCredit(d("2026-10-02"), "Payer", BigDecimal("500"))), repo.incomes(user, range, ist))
        assertEquals(d("2026-10-01"), repo.firstActivityDay(user, ist))
        assertTrue(repo.spending(UUID.randomUUID(), range, ist).isEmpty())
        assertNull(repo.firstActivityDay(UUID.randomUUID(), ist))
    }
}
