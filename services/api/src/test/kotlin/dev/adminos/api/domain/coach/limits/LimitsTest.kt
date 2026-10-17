package dev.adminos.api.domain.coach.limits

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.CoachFixture.IST
import dev.adminos.api.domain.coach.CoachFixture.USER
import dev.adminos.api.domain.coach.CoachFixture.txn
import dev.adminos.api.domain.coach.leaks.LeakFact
import dev.adminos.api.domain.coach.lenses.LensCatalogue
import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-904: scopes, periods, thresholds, the learning month and the suggestion rule, as pure functions. */
class LimitsTest {
    private val qc = LensId("quick_commerce")
    private fun fact(merchant: String, amount: String, day: LocalDate, lenses: Set<LensId> = emptySet(), nature: TransactionNature = TransactionNature.SPEND,
                     category: TransactionCategory = TransactionCategory.SHOPPING, person: Boolean = false) =
        LeakFact(txn(merchant, amount, day.atTime(12, 0), nature = nature, category = category), lenses, day.atTime(12, 0), timeKnown = true, person = person)

    private fun limit(scope: LimitScope, amount: String = "1000.00", period: LimitPeriod = LimitPeriod.WEEKLY) =
        SpendingLimit(userId = USER, scope = scope, label = "X", period = period, amount = BigDecimal(amount), createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)

    @Test
    fun `weeks run Monday to Sunday and months are calendar months, across year and leap days`() {
        assertEquals(DateRange(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 6)), LimitPeriod.WEEKLY.rangeOf(LocalDate.of(2026, 12, 3)))
        assertEquals(DateRange(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3)), LimitPeriod.WEEKLY.rangeOf(LocalDate.of(2027, 1, 3)))
        assertEquals(DateRange(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29)), LimitPeriod.MONTHLY.rangeOf(LocalDate.of(2028, 2, 15)))
        assertEquals(DateRange(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 22)), LimitPeriod.WEEKLY.rangeOf(LocalDate.of(2026, 11, 16)))
    }

    @Test
    fun `each scope counts its own facts, refunds net off, settle-ups stay out of categories`() {
        val day = LocalDate.of(2026, 11, 17)
        val facts = listOf(
            fact("Zepto", "400.00", day, setOf(qc)), fact("Zepto", "100.00", day, setOf(qc), TransactionNature.REFUND),
            fact("Blinkit", "300.00", day, setOf(qc)), fact("Rohit Sharma", "900.00", day, category = TransactionCategory.SHOPPING, person = true)
        )
        assertEquals(0, LimitMath.spent(LimitScope.Lens(qc), facts).compareTo(BigDecimal("600")))
        assertEquals(0, LimitMath.spent(LimitScope.Merchant(" ZEPTO "), facts).compareTo(BigDecimal("300")))
        assertEquals(0, LimitMath.spent(LimitScope.Category(TransactionCategory.SHOPPING), facts).compareTo(BigDecimal("600")))
        assertEquals(0, LimitMath.spent(LimitScope.Merchant("Rohit Sharma"), facts).compareTo(BigDecimal("900")), "a merchant the owner named counts even if it is a person")
    }

    @Test
    fun `thresholds and status`() {
        val l = limit(LimitScope.Lens(qc))
        val day = LocalDate.of(2026, 11, 18)
        val s = LimitMath.status(l, day, listOf(fact("Zepto", "800.00", day, setOf(qc))))
        assertEquals(listOf(Threshold.HALF, Threshold.MOST), s.reached)
        assertEquals(BigDecimal("0.8000"), s.used)
        assertEquals(0, s.left.compareTo(BigDecimal("200")))
        assertEquals(4, s.daysLeft)
        assertTrue(Threshold.ALL.reachedBy(BigDecimal("1000.00"), BigDecimal("1000.00")))
        assertFalse(Threshold.HALF.reachedBy(BigDecimal("499.99"), BigDecimal("1000.00")))
        assertEquals(emptyList(), LimitMath.status(l, day, listOf(fact("Zepto", "50.00", day, setOf(qc), TransactionNature.REFUND))).reached)
    }

    @Test
    fun `limits and scopes reject bad values`() {
        for (a in listOf("0", "-1", "10000000.01", "1.001")) assertFailsWith<IllegalArgumentException>(a) { limit(LimitScope.Lens(qc), a) }
        assertFailsWith<IllegalArgumentException> { LimitScope.Merchant("  ") }
        assertFailsWith<IllegalArgumentException> { LimitScope.Merchant("x".repeat(256)) }
        assertFailsWith<IllegalArgumentException> { LimitScope.Category(TransactionCategory.TRANSFER) }
        assertFailsWith<IllegalArgumentException> { SpendingLimit(userId = USER, scope = LimitScope.Lens(qc), label = " ", period = LimitPeriod.WEEKLY, amount = BigDecimal.TEN, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH) }
    }

    @Test
    fun `the learning month counts local days from the first spend`() {
        val today = LocalDate.of(2026, 11, 18)
        assertEquals(LearningState(0), LearningState.of(null, today, IST))
        assertEquals("Learning your habits: 0 of 30 days", LearningState(0).label)
        // 20:00 UTC on 19 Oct is 01:30 on 20 Oct in India: day 1 is 20 Oct, so 18 Nov is day 30.
        val first = LocalDateTime.of(2026, 10, 19, 20, 0).atZone(ZoneId.of("UTC")).toInstant()
        val s = LearningState.of(first, today, IST)
        assertEquals(30, s.daysOfData); assertTrue(s.ready)
        val early = LearningState.of(first, LocalDate.of(2026, 11, 17), IST)
        assertEquals(29, early.daysOfData); assertFalse(early.ready)
        assertEquals("Learning your habits: 29 of 30 days", early.label)
        assertEquals("Learning your habits: 30 of 30 days", LearningState(400).label)
        assertEquals(LearningState(0), LearningState.of(Instant.parse("2026-12-01T00:00:00Z"), today, IST), "a future-dated first spend is no history")
        assertFailsWith<IllegalArgumentException> { LearningState(-1) }
    }

    @Test
    fun `the suggestion is the median of the last four weeks less ten percent, rounded down to fifty`() {
        val today = LocalDate.of(2026, 11, 18) // week of Mon 16 Nov; last complete weeks: 19 Oct … 9 Nov
        val weeks = LimitSuggester.weeksBefore(today)
        assertEquals(listOf("2026-10-19", "2026-10-26", "2026-11-02", "2026-11-09"), weeks.map { it.from.toString() })
        val qcLens = LensCatalogue.DEFAULT.byId(qc)!!
        val facts = listOf("1800.00", "2400.00", "2000.00", "9000.00").zip(weeks).map { (a, w) -> fact("Zepto", a, w.from.plusDays(2), setOf(qc)) } +
            fact("Zepto", "5000.00", today, setOf(qc)) // this week: not used
        val out = LimitSuggester.suggest(LimitSuggester.Input(today, Instant.parse("2026-11-18T06:30:00Z"), listOf(qcLens), facts, emptySet(), emptyMap())).single()
        assertEquals(BigDecimal("2200.00"), out.median)            // (2000 + 2400) / 2
        assertEquals(BigDecimal("1950.00"), out.amount)            // 1980 → 1950
        assertEquals(LimitPeriod.WEEKLY, out.period)
        assertEquals(listOf("1800.00", "2400.00", "2000.00", "9000.00"), out.weeks.map { it.spent.setScale(2).toPlainString() })
    }

    @Test
    fun `no suggestion for a rare habit, a small one, a lens with a limit or a recent not now`() {
        val today = LocalDate.of(2026, 11, 18)
        val weeks = LimitSuggester.weeksBefore(today)
        val qcLens = LensCatalogue.DEFAULT.byId(qc)!!
        val now = Instant.parse("2026-11-18T06:30:00Z")
        fun run(facts: List<LeakFact>, limited: Set<LensId> = emptySet(), dismissed: Map<LensId, Instant> = emptyMap()) =
            LimitSuggester.suggest(LimitSuggester.Input(today, now, listOf(qcLens), facts, limited, dismissed))
        val oneWeek = listOf(fact("Zepto", "3000.00", weeks[0].from, setOf(qc)))
        assertTrue(run(oneWeek).isEmpty(), "spending in only 1 of 4 weeks")
        val small = weeks.map { fact("Zepto", "100.00", it.from, setOf(qc)) }
        assertTrue(run(small).isEmpty(), "₹90 rounds below the ₹100 floor")
        val regular = weeks.map { fact("Zepto", "1000.00", it.from, setOf(qc)) }
        assertEquals(BigDecimal("900.00"), run(regular).single().amount)
        assertTrue(run(regular, limited = setOf(qc)).isEmpty())
        assertTrue(run(regular, dismissed = mapOf(qc to now.minus(Duration.ofDays(29)))).isEmpty())
        assertEquals(1, run(regular, dismissed = mapOf(qc to now.minus(Duration.ofDays(31)))).size)
    }

    @Test
    fun `the median of odd and even counts`() {
        assertEquals(BigDecimal("2.00"), LimitSuggester.median(listOf(BigDecimal(3), BigDecimal(1), BigDecimal(2))))
        assertEquals(BigDecimal("2.50"), LimitSuggester.median(listOf(BigDecimal(4), BigDecimal(1), BigDecimal(2), BigDecimal(3))))
        assertFailsWith<IllegalArgumentException> { LimitSuggester.median(emptyList()) }
    }
}
