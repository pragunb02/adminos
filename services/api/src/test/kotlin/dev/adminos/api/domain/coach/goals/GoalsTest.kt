package dev.adminos.api.domain.coach.goals

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.CoachFixture.IST
import dev.adminos.api.domain.coach.CoachFixture.USER
import dev.adminos.api.domain.coach.CoachFixture.txn
import dev.adminos.api.domain.coach.CoachKit
import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.coach.limits.LimitPeriod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** AOS-907: cut and cap goals, weekly progress and no-spend streaks. "Today" is Wed 18 Nov 2026 in India. */
class GoalsTest {
    private val qc = LensId("quick_commerce")

    private suspend fun CoachKit.order(amount: String, at: LocalDateTime, nature: TransactionNature = TransactionNature.SPEND) =
        transactions.save(txn("Zepto", amount, at, nature = nature, category = TransactionCategory.SHOPPING, subcategory = "groceries", user = user))

    private suspend fun CoachKit.baselineMonths() {
        for (m in 8..10) for (d in listOf(5, 15, 25)) order("1000.00", LocalDateTime.of(2026, m, d, 20, 0))
    }

    @Test
    fun `a cut goal freezes its baseline from the last three full months`() = runBlocking<Unit> {
        val k = CoachKit()
        k.baselineMonths()
        val weekly = k.goals.create(k.user, IST, GoalRequest("quick_commerce", GoalKind.CUT_PERCENT, BigDecimal(30), LimitPeriod.WEEKLY))
        assertIs<GoalOutcome.Saved>(weekly)
        val g = weekly.progress.goal
        assertEquals(BigDecimal("684.78"), g.baseline)   // ₹9,000 × 7 ÷ 92 days (Aug–Oct)
        assertEquals(BigDecimal("479.00"), g.target)     // 70%, rounded down to the rupee
        assertEquals(LocalDate.of(2026, 11, 18), g.startedOn)

        val other = CoachKit().also { it.baselineMonths() }
        val monthly = other.goals.create(other.user, IST, GoalRequest("quick_commerce", GoalKind.CUT_PERCENT, BigDecimal(25), LimitPeriod.MONTHLY)) as GoalOutcome.Saved
        assertEquals(BigDecimal("3000.00"), monthly.progress.goal.baseline)
        assertEquals(BigDecimal("2250.00"), monthly.progress.goal.target)
    }

    @Test
    fun `a cut needs something to cut from, a cap doesn't`() = runBlocking<Unit> {
        val k = CoachKit()
        val out = k.goals.create(k.user, IST, GoalRequest("quick_commerce", GoalKind.CUT_PERCENT, BigDecimal(30), LimitPeriod.WEEKLY))
        assertIs<GoalOutcome.NoBaseline>(out)
        assertEquals(DateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 31)), out.months)
        val cap = k.goals.create(k.user, IST, GoalRequest("quick_commerce", GoalKind.CAP_AMOUNT, BigDecimal(1500), LimitPeriod.WEEKLY))
        assertEquals(BigDecimal("1500"), (cap as GoalOutcome.Saved).progress.goal.target)
    }

    @Test
    fun `one goal per lens, unknown lenses refused, deletes only your own`() = runBlocking<Unit> {
        val k = CoachKit()
        val g = (k.goals.create(k.user, IST, GoalRequest("travel", GoalKind.CAP_AMOUNT, BigDecimal(5000), LimitPeriod.MONTHLY)) as GoalOutcome.Saved).progress.goal
        assertIs<GoalOutcome.Duplicate>(k.goals.create(k.user, IST, GoalRequest("travel", GoalKind.CAP_AMOUNT, BigDecimal(1), LimitPeriod.WEEKLY)))
        assertIs<GoalOutcome.UnknownLens>(k.goals.create(k.user, IST, GoalRequest("gambling", GoalKind.CAP_AMOUNT, BigDecimal(1), LimitPeriod.WEEKLY)))
        assertIs<GoalOutcome.UnknownLens>(k.goals.create(k.user, IST, GoalRequest("../x", GoalKind.CAP_AMOUNT, BigDecimal(1), LimitPeriod.WEEKLY)))
        assertFalse(k.goals.delete(UUID.randomUUID(), g.id))
        assertTrue(k.goals.delete(k.user, g.id))
        assertTrue(k.goals.list(k.user, IST).isEmpty())
    }

    @Test
    fun `weekly progress over the weeks since the goal began, refunds netting off`() = runBlocking<Unit> {
        val k = CoachKit()
        k.clock.at(LocalDateTime.of(2026, 11, 2, 9, 0)) // set on Mon 2 Nov
        k.goals.create(k.user, IST, GoalRequest("quick_commerce", GoalKind.CAP_AMOUNT, BigDecimal(1000), LimitPeriod.WEEKLY))
        k.order("800.00", LocalDateTime.of(2026, 11, 3, 20, 0))          // week of 2 Nov: met
        k.order("1500.00", LocalDateTime.of(2026, 11, 10, 20, 0))        // week of 9 Nov: missed …
        k.order("400.00", LocalDateTime.of(2026, 11, 12, 20, 0), TransactionNature.REFUND) // … ₹1,100 after the refund
        k.order("300.00", LocalDateTime.of(2026, 11, 16, 13, 0))         // this week so far
        k.clock.at(LocalDateTime.of(2026, 11, 18, 12, 0))
        val p = k.goals.list(k.user, IST).single()
        assertEquals(listOf("2026-11-02", "2026-11-09", "2026-11-16"), p.periods.map { it.range.from.toString() })
        assertEquals(listOf(true, false, true), p.periods.map { it.met })
        assertEquals(0, p.periods[1].spent.compareTo(BigDecimal("1100")))
        assertTrue(p.currentPeriod.current)
        assertEquals(1, p.metCount); assertEquals(2, p.completedCount)
        assertEquals("Quick commerce", p.label)
    }

    @Test
    fun `no-spend streaks count days without orders, refunds don't break them, and days before any data don't count`() {
        val days = { d: Int -> LocalDate.of(2026, 11, d) }
        fun f(day: Int, nature: TransactionNature = TransactionNature.SPEND) = dev.adminos.api.domain.coach.leaks.LeakFact(
            txn("Zepto", "100.00", days(day).atTime(12, 0), nature = nature), setOf(qc), days(day).atTime(12, 0), timeKnown = true)
        val facts = listOf(f(3), f(4), f(12), f(14, TransactionNature.REFUND))
        // since 1 Nov: no orders 1–2 (2), 5–11 (7), 13–18 (6, the refund on the 14th doesn't count)
        assertEquals(Streak(current = 6, best = 7), GoalMath.streak(qc, facts, since = days(1), today = days(18)))
        assertEquals(Streak(current = 0, best = 7), GoalMath.streak(qc, facts + f(18), since = days(1), today = days(18)))
        assertEquals(Streak(current = 1, best = 1), GoalMath.streak(qc, emptyList(), since = days(18), today = days(18)))
    }

    @Test
    fun `goal values are validated`() {
        val base = Goal(userId = USER, lens = qc, kind = GoalKind.CAP_AMOUNT, value = BigDecimal(100), period = LimitPeriod.WEEKLY, baseline = null,
            target = BigDecimal(100), startedOn = LocalDate.of(2026, 11, 18), createdAt = Instant.EPOCH)
        assertFailsWith<IllegalArgumentException> { base.copy(value = BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { base.copy(value = BigDecimal("1.001")) }
        assertFailsWith<IllegalArgumentException> { base.copy(kind = GoalKind.CUT_PERCENT, value = BigDecimal(91), baseline = BigDecimal.TEN) }
        assertFailsWith<IllegalArgumentException> { base.copy(kind = GoalKind.CUT_PERCENT, value = BigDecimal("10.5"), baseline = BigDecimal.TEN) }
        assertFailsWith<IllegalArgumentException> { base.copy(kind = GoalKind.CUT_PERCENT, value = BigDecimal(10), baseline = null) }
        assertFailsWith<IllegalArgumentException> { base.copy(target = BigDecimal(-1)) }
        assertEquals(DateRange(LocalDate.of(2025, 11, 1), LocalDate.of(2026, 1, 31)), Goal.baselineMonths(LocalDate.of(2026, 2, 10)))
    }

    @Test
    fun `at most twelve periods are shown`() = runBlocking<Unit> {
        val k = CoachKit()
        k.clock.at(LocalDateTime.of(2026, 1, 5, 9, 0))
        k.goals.create(k.user, IST, GoalRequest("travel", GoalKind.CAP_AMOUNT, BigDecimal(1000), LimitPeriod.WEEKLY))
        k.clock.advance(Duration.ofDays(200))
        assertEquals(GoalMath.MAX_PERIODS, k.goals.list(k.user, IST).single().periods.size)
    }
}
