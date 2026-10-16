package dev.adminos.api.domain.analytics

import dev.adminos.api.domain.analytics.AnalyticsFixture.IST
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.TransactionCategory
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-301: one definition of spent/received; totals agree across summary, categories and series (D-46). */
class AnalyticsServiceTest {
    private val user = UUID.randomUUID()
    private val txns = InMemoryTransactionRepository()
    private val now = LocalDate.of(2026, 10, 16).atTime(12, 0).atZone(IST).toInstant()
    private val service = AnalyticsService(InMemoryAnalyticsRepository(txns), Clock.fixed(now, IST))
    private val october = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 16))

    init { runBlocking { AnalyticsFixture.all(user).forEach { txns.save(it) } } }

    private fun d(s: String) = BigDecimal(s)
    private fun eq(expected: String, actual: BigDecimal, what: String = "") = assertEquals(0, d(expected).compareTo(actual), "$what: $actual")

    @Test
    fun `default range is this month so far in the user's zone`() {
        assertEquals(october, service.defaultRange(IST))
    }

    @Test
    fun `spent is spend minus refunds, received is income, transfers and reversals are ignored`() = runBlocking<Unit> {
        val s = service.summary(user, october, IST)
        eq("1249.00", s.current.spent, "300+999+200+50−300")
        eq("100000.00", s.current.received)
        eq("98751.00", s.current.net)
        assertEquals(4, s.spendCount)
    }

    @Test
    fun `summary compares with the previous period of the same length`() = runBlocking<Unit> {
        val s = service.summary(user, october, IST)
        assertEquals(DateRange(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 30)), s.previousRange)
        eq("1000.00", s.previous.spent)
        eq("24.9", s.spentChangePct!!)
        assertNull(service.summary(user, DateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2)), IST).spentChangePct, "nothing to compare with")
    }

    @Test
    fun `total equals the sum of categories and of series buckets`() = runBlocking<Unit> {
        val total = service.summary(user, october, IST).current.spent
        val cats = service.categories(user, october, IST)
        for (b in Bucket.entries) {
            eq(total.toPlainString(), service.series(user, october, b, IST).fold(BigDecimal.ZERO) { a, p -> a + p.money.spent }, "series $b")
        }
        eq(total.toPlainString(), cats.fold(BigDecimal.ZERO) { a, c -> a + c.spent }, "categories")
    }

    @Test
    fun `categories are netted, ranked, with share, count and previous spend`() = runBlocking<Unit> {
        val (shopping, food) = service.categories(user, october, IST)
        assertEquals(TransactionCategory.SHOPPING, shopping.category); eq("699.00", shopping.spent); assertEquals(1, shopping.count)
        assertEquals(TransactionCategory.FOOD, food.category); eq("550.00", food.spent); assertEquals(3, food.count)
        eq("1000.00", food.previousSpent)
        assertEquals(0.5596, shopping.share, 0.0001)
    }

    @Test
    fun `daily series is zero-filled and uses the user's local day`() = runBlocking<Unit> {
        val days = service.series(user, october, Bucket.DAY, IST)
        assertEquals(16, days.size)
        eq("0", days[2].money.spent, "Oct 3 had only a transfer")
        eq("250.00", days[4].money.spent, "Oct 5: 200 + 50 at 23:30 IST")
        eq("-300.00", days[8].money.spent, "Oct 9: a refund-only day is negative, not hidden")
    }

    @Test
    fun `weeks start on Monday and months on the 1st`() = runBlocking<Unit> {
        val weeks = service.series(user, october, Bucket.WEEK, IST)
        assertEquals(listOf("2026-09-28", "2026-10-05", "2026-10-12"), weeks.map { it.start.toString() })
        eq("1299.00", weeks[0].money.spent, "Oct 1–4"); eq("-50.00", weeks[1].money.spent, "Oct 5–11")
        assertEquals(listOf("2026-10-01"), service.series(user, october, Bucket.MONTH, IST).map { it.start.toString() })
    }

    @Test
    fun `another timezone moves the late-night payment to the next day`() = runBlocking<Unit> {
        val london = ZoneId.of("Europe/London") // 23:30 IST on Oct 5 is 19:00 in London: still Oct 5
        val tokyo = ZoneId.of("Asia/Tokyo")     // … and 03:00 on Oct 6 in Tokyo
        eq("250.00", service.series(user, october, Bucket.DAY, london)[4].money.spent)
        val tokyoDays = service.series(user, october, Bucket.DAY, tokyo)
        eq("200.00", tokyoDays[4].money.spent); eq("50.00", tokyoDays[5].money.spent)
    }

    @Test
    fun `top merchants by spent, unnamed ones grouped as Unknown`() = runBlocking<Unit> {
        val top = service.merchants(user, october, IST, limit = 10)
        assertEquals(listOf("Amazon", "Swiggy", "Unknown"), top.map { it.merchant })
        eq("699.00", top[0].spent); assertEquals(2, top[1].count)
        assertEquals(1, service.merchants(user, october, IST, limit = 1).size)
    }

    @Test
    fun `bad ranges are rejected with the parameter named`() = runBlocking<Unit> {
        assertTrue(assertFailsWith<IllegalArgumentException> { service.validate(DateRange(october.to, october.from)) }.message!!.startsWith("to"))
        assertFailsWith<IllegalArgumentException> { service.validate(DateRange(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1))) }
        val tooMany = assertFailsWith<IllegalArgumentException> { service.series(user, DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 3, 1)), Bucket.DAY, IST) }
        assertTrue(tooMany.message!!.startsWith("bucket"))
    }
}
