package dev.adminos.api.domain.coach.leaks

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.CoachFixture.IST
import dev.adminos.api.domain.coach.CoachFixture.USER
import dev.adminos.api.domain.coach.CoachFixture.txn
import dev.adminos.api.domain.coach.lenses.InMemoryLensOverrideRepository
import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.coach.lenses.LensMatcher
import dev.adminos.api.domain.coach.lenses.LensService
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-902: the leaks numbers per lens and merchant, refunds, late night, weekdays, zones, and the drill-down. */
class LeakServiceTest {
    private val week = DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18))
    private val clock = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC)

    private fun service(txns: TransactionRepository = InMemoryTransactionRepository(), lenses: LensService = LensService(LensMatcher(), InMemoryLensOverrideRepository())) =
        LeakService(txns, lenses, clock)

    private suspend fun withWeek(): Pair<LeakService, LensService> {
        val repo = InMemoryTransactionRepository()
        LeakFixture.week(USER).forEach { repo.save(it) }
        val lenses = LensService(LensMatcher(), InMemoryLensOverrideRepository())
        return service(repo, lenses) to lenses
    }

    @Test
    fun `a week of leaks per lens and merchant`() = runBlocking<Unit> {
        val (svc, _) = withWeek()
        assertWeek(svc.report(USER, week, IST))
    }

    @Test
    fun `the drill-down lists exactly what each number is made of`() = runBlocking<Unit> {
        val (svc, _) = withWeek()
        assertDrillDown(svc)
    }

    @Test
    fun `an owner's removal takes a merchant out of the numbers and the drill-down`() = runBlocking<Unit> {
        val (svc, lenses) = withWeek()
        lenses.setOverride(USER, "quick_commerce", "Blinkit", include = false)
        val qc = svc.report(USER, week, IST).lenses.first { it.lens.id.value == "quick_commerce" }
        assertEquals(BigDecimal("900.00"), qc.numbers.spent)
        assertEquals(listOf("Zepto"), qc.merchants.map { it.merchant })
        assertEquals(0, svc.transactions(USER, week, IST, LeakFilter(merchant = "Blinkit"), 50).total)
    }

    @Test
    fun `an owner's addition pulls a friend's payment in`() = runBlocking<Unit> {
        val (svc, lenses) = withWeek()
        lenses.setOverride(USER, "nights_out", "Rohit Sharma", include = true)
        val nights = svc.report(USER, week, IST).lenses.first { it.lens.id.value == "nights_out" }
        assertEquals(BigDecimal("1500.00"), nights.numbers.spent)
        assertEquals(1, nights.numbers.lateNight.orders)
    }

    @Test
    fun `no data gives zero numbers, seven empty weekdays and no averages`() = runBlocking<Unit> {
        val report = service().report(USER, week, IST)
        assertEquals(BigDecimal.ZERO, report.total.spent)
        assertEquals(6, report.lenses.size)
        report.lenses.forEach { l ->
            assertEquals(0, l.numbers.orders)
            assertNull(l.numbers.averageOrder)
            assertNull(l.numbers.lateNight.share)
            assertNull(l.numbers.changePct)
            assertEquals(DayOfWeek.values().toList(), l.numbers.weekdays.map { it.day })
            assertTrue(l.merchants.isEmpty())
        }
        assertEquals(listOf("quick_commerce", "food_delivery", "nights_out", "shopping", "pay_later", "travel"), report.lenses.map { it.lens.id.value })
    }

    @Test
    fun `days and late night follow the user's zone`() = runBlocking<Unit> {
        val repo = InMemoryTransactionRepository()
        // 19:00 UTC on Sun 11 Oct is 00:30 on Mon 12 Oct in India: in this week, and late at night.
        repo.save(txn("Zepto", "200.00", LocalDateTime.of(2026, 10, 11, 19, 0), zone = ZoneOffset.UTC))
        // 18:00 UTC on Sun 18 Oct is 23:30 the same Sunday in India: in; 18:31 UTC is Mon 19 Oct 00:01: out.
        repo.save(txn("Zepto", "300.00", LocalDateTime.of(2026, 10, 18, 18, 0), zone = ZoneOffset.UTC))
        repo.save(txn("Zepto", "999.00", LocalDateTime.of(2026, 10, 18, 18, 31), zone = ZoneOffset.UTC))
        val qc = service(repo).report(USER, week, IST).lenses.first { it.lens.id.value == "quick_commerce" }
        assertEquals(BigDecimal("500.00"), qc.numbers.spent)
        assertEquals(2, qc.numbers.lateNight.orders)
        assertEquals(1, qc.numbers.weekdays.first { it.day == DayOfWeek.MONDAY }.orders)
        assertEquals(1, qc.numbers.weekdays.first { it.day == DayOfWeek.SUNDAY }.orders)
    }

    @Test
    fun `a daylight-saving change keeps local days and late night right`() = runBlocking<Unit> {
        val ny = ZoneId.of("America/New_York") // clocks go back at 02:00 on Sun 1 Nov 2026
        val repo = InMemoryTransactionRepository()
        repo.save(txn("Zepto", "10.00", LocalDateTime.of(2026, 11, 1, 1, 30), zone = ny))   // 01:30 (first, EDT)
        repo.save(txn("Zepto", "20.00", LocalDateTime.of(2026, 11, 1, 22, 30), zone = ny))  // 22:30 EST
        repo.save(txn("Zepto", "40.00", LocalDateTime.of(2026, 11, 2, 0, 30), zone = ny))   // next day
        val day = DateRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1))
        val qc = service(repo).report(USER, day, ny).lenses.first { it.lens.id.value == "quick_commerce" }
        assertEquals(BigDecimal("30.00"), qc.numbers.spent)
        assertEquals(2, qc.numbers.lateNight.orders)
        assertEquals(BigDecimal("1.0000"), qc.numbers.lateNight.share)
    }

    @Test
    fun `the default range is this month so far in the user's zone`() {
        // 12:00 UTC on 18 Oct is 17:30 in India.
        assertEquals(DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 18)), service().defaultRange(IST))
        val kiritimati = ZoneId.of("Pacific/Kiritimati") // UTC+14: already the 19th
        assertEquals(LocalDate.of(2026, 10, 19), service().defaultRange(kiritimati).to)
    }

    @Test
    fun `ranges are validated`() {
        val svc = service()
        assertFailsWith<IllegalArgumentException> { svc.validate(DateRange(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1))) }
        assertFailsWith<IllegalArgumentException> { svc.validate(DateRange(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 10, 1))) }
        assertFailsWith<IllegalArgumentException> { runBlocking { svc.transactions(USER, week, IST, LeakFilter(), 0) } }
        assertFailsWith<IllegalArgumentException> { LeakFilter(merchant = " ") }
        assertFailsWith<IllegalArgumentException> { LeakFilter(nature = TransactionNature.INCOME) }
    }

    @Test
    fun `a merchant with no name is grouped as Unknown and found by that name`() = runBlocking<Unit> {
        val repo = InMemoryTransactionRepository()
        repo.save(txn(null, "120.00", LocalDateTime.of(2026, 10, 13, 20, 0), category = TransactionCategory.FOOD, subcategory = "food_delivery"))
        repo.save(txn("  ", "80.00", LocalDateTime.of(2026, 10, 13, 21, 0), category = TransactionCategory.FOOD, subcategory = "food_delivery"))
        val svc = service(repo)
        val food = svc.report(USER, week, IST).lenses.first { it.lens.id.value == "food_delivery" }
        assertEquals(listOf("Unknown"), food.merchants.map { it.merchant })
        assertEquals(2, svc.transactions(USER, week, IST, LeakFilter(lens = LensId("food_delivery"), merchant = "unknown"), 50).total)
    }

    @Test
    fun `merchant spellings that differ by case are one merchant shown in the most used spelling`() = runBlocking<Unit> {
        val repo = InMemoryTransactionRepository()
        repo.save(txn("ZEPTO", "100.00", LocalDateTime.of(2026, 10, 13, 12, 0)))
        repo.save(txn("Zepto", "100.00", LocalDateTime.of(2026, 10, 13, 13, 0)))
        repo.save(txn("Zepto", "100.00", LocalDateTime.of(2026, 10, 13, 14, 0)))
        val qc = service(repo).report(USER, week, IST).lenses.first { it.lens.id.value == "quick_commerce" }
        assertEquals(listOf("Zepto" to 3), qc.merchants.map { it.merchant to it.numbers.orders })
    }

    @Test
    fun `the list is capped but the count is complete`() = runBlocking<Unit> {
        val (svc, _) = withWeek()
        val page = svc.transactions(USER, week, IST, LeakFilter(), 2)
        assertEquals(2, page.items.size)
        assertEquals(7, page.total)
    }

    companion object {
        private fun money(s: String) = BigDecimal(s)
        private fun LeakReport.lens(id: String) = lenses.first { it.lens.id.value == id }

        /** The expected numbers for [LeakFixture.week]; shared with the Postgres test. */
        fun assertWeek(r: LeakReport) {
            assertEquals(DateRange(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11)), r.previousRange)
            assertEquals(listOf("quick_commerce", "food_delivery", "nights_out", "pay_later", "travel", "shopping"), r.lenses.map { it.lens.id.value })

            val qc = r.lens("quick_commerce").numbers
            assertEquals(0, qc.spent.compareTo(money("1200")))
            assertEquals(3, qc.orders)
            assertEquals(money("433.33"), qc.averageOrder)
            assertEquals(2, qc.lateNight.orders); assertEquals(3, qc.lateNight.timedOrders)
            assertEquals(money("0.6667"), qc.lateNight.share)
            assertEquals(0, qc.lateNight.spent.compareTo(money("900")))
            assertEquals(0, qc.previousSpent.compareTo(money("500")))
            assertEquals(money("140.0"), qc.changePct)
            val days = qc.weekdays.associateBy { it.day }
            assertEquals(1, days.getValue(DayOfWeek.TUESDAY).orders)
            assertEquals(0, days.getValue(DayOfWeek.THURSDAY).orders)
            assertEquals(0, days.getValue(DayOfWeek.THURSDAY).spent.compareTo(money("-100")))

            val merchants = r.lens("quick_commerce").merchants
            assertEquals(listOf("Zepto", "Blinkit"), merchants.map { it.merchant })
            assertEquals(0, merchants[0].numbers.spent.compareTo(money("900")))
            assertEquals(0, merchants[0].numbers.previousSpent.compareTo(money("500")))
            assertNull(merchants[1].numbers.changePct)

            val food = r.lens("food_delivery").numbers
            assertEquals(0, food.spent.compareTo(money("750")))
            assertEquals(2, food.orders)
            assertEquals(0, food.lateNight.orders); assertEquals(1, food.lateNight.timedOrders, "the statement row has no time")
            assertEquals(money("0.0000"), food.lateNight.share)

            val shopping = r.lens("shopping").numbers
            assertEquals(0, shopping.spent.compareTo(money("-999")), "a refund alone shows negative, not clipped")
            assertEquals(0, shopping.orders)
            assertNull(shopping.averageOrder)

            assertEquals(0, r.lens("nights_out").numbers.spent.signum(), "a friend's UPI payment is a settle-up, not a night out")
            assertEquals(0, r.total.spent.compareTo(money("951")))
            assertEquals(5, r.total.orders)
        }

        suspend fun assertDrillDown(svc: LeakService, user: java.util.UUID = USER) {
            val week = DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18))
            fun amounts(l: LeakTransactions) = l.items.map { it.amount.stripTrailingZeros().toPlainString() }
            val qc = LensId("quick_commerce")
            assertEquals(listOf("100", "300", "600", "400"), amounts(svc.transactions(user, week, IST, LeakFilter(lens = qc), 50)))
            assertEquals(listOf("300", "600"), amounts(svc.transactions(user, week, IST, LeakFilter(lens = qc, lateNight = true), 50)))
            assertEquals(listOf("600"), amounts(svc.transactions(user, week, IST, LeakFilter(lens = qc, weekday = DayOfWeek.TUESDAY), 50)))
            assertEquals(listOf("100", "600", "400"), amounts(svc.transactions(user, week, IST, LeakFilter(lens = qc, merchant = "zepto"), 50)))
            assertEquals(listOf("100"), amounts(svc.transactions(user, week, IST, LeakFilter(lens = qc, nature = TransactionNature.REFUND), 50)))
            assertEquals(listOf("300"), amounts(svc.transactions(user, week, IST, LeakFilter(search = "blink"), 50)))
            assertEquals(listOf("250", "500"), amounts(svc.transactions(user, week, IST, LeakFilter(category = TransactionCategory.FOOD), 50)))
            // All leaks: every lens, each once; never the friend, the transfer or the salary.
            assertEquals(7, svc.transactions(user, week, IST, LeakFilter(), 50).total)
            val previous = svc.transactions(user, week.previous(), IST, LeakFilter(lens = qc), 50)
            assertEquals(listOf("500"), amounts(previous))
        }
    }
}
