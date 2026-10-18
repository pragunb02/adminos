package dev.adminos.api.domain.coach

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.CoachFixture.IST
import dev.adminos.api.domain.coach.CoachFixture.txn
import dev.adminos.api.domain.coach.goals.GoalKind
import dev.adminos.api.domain.coach.goals.GoalOutcome
import dev.adminos.api.domain.coach.goals.GoalRequest
import dev.adminos.api.domain.coach.leaks.LeakFact
import dev.adminos.api.domain.coach.leaks.LeakFilter
import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.coach.limits.LimitOutcome
import dev.adminos.api.domain.coach.limits.LimitPeriod
import dev.adminos.api.domain.coach.limits.LimitRequest
import dev.adminos.api.domain.coach.limits.LimitScope
import dev.adminos.api.domain.coach.savings.Assumptions
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * AOS-914: leaks, limits, goals and the savings rate count only the owner's share of a split bill (D-111), the same
 * [Transaction.countedAmount] the console's analytics sum; settle-ups with friends (nature `settlement`) are neither
 * spending nor income anywhere. "Now" is Wed 18 Nov 2026, 12:00 in India; this week is Mon 16 – Sun 22 Nov.
 *
 * The scenario: a ₹4,000 dinner at a pub the owner paid for four, their share ₹1,000 (set by the Splitwise import,
 * like the real path, after the bank transaction exists); a friend settles up ₹3,000 to the owner; the owner settles
 * ₹450 they owed another friend. The settle-ups name the pub on purpose: if they were counted, the lens would catch them.
 */
class SplitShareTest {
    private val nightsOut = LensId("nights_out")
    private fun nov(d: Int, h: Int = 12, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m)

    /** The bank's debit for the whole bill, then the import's share on it (TransactionRepository.setOwnShare). */
    private suspend fun CoachKit.splitBill(amount: String, share: String, at: LocalDateTime, merchant: String = "Toit"): Transaction {
        val t = transactions.save(txn(merchant, amount, at, user = user))
        assertTrue(transactions.setOwnShare(t.id, BigDecimal(share)))
        return transactions.findById(t.id)!!
    }

    private suspend fun CoachKit.settlement(amount: String, at: LocalDateTime, received: Boolean, merchant: String = "Toit") =
        transactions.save(txn(merchant, amount, at, nature = TransactionNature.SETTLEMENT, user = user)
            .let { if (received) it.copy(type = TransactionType.CREDIT) else it })

    private suspend fun CoachKit.scenario(): CoachKit {
        splitBill("4000.00", "1000.00", nov(17, 22, 30))           // late night, so the late-night numbers are tested too
        settlement("3000.00", nov(17, 23, 0), received = true)
        settlement("450.00", nov(16, 20, 0), received = false)
        transactions.save(txn("Zepto", "500.00", nov(16, 19, 0), user = user))  // an ordinary, unsplit leak
        return this
    }

    private fun learned() = CoachKit().also { k -> runBlocking { k.history(LocalDateTime.of(2026, 10, 1, 9, 0)) } }

    // ── Leaks ──

    @Test
    fun `leaks count the owner's share of a split bill and never a settlement`() = runBlocking<Unit> {
        val k = CoachKit().scenario()
        val week = DateRange(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 18))
        val r = k.leaks.report(k.user, week, IST)
        val n = r.lenses.single { it.lens.id == nightsOut }.numbers
        assertEquals(0, n.spent.compareTo(BigDecimal("1000")), "₹1,000 share, not the ₹4,000 bill")
        assertEquals(1, n.orders, "the settlements are not orders")
        assertEquals(0, n.ordered.compareTo(BigDecimal("1000")))
        assertEquals(BigDecimal("1000.00"), n.averageOrder)
        assertEquals(1, n.lateNight.orders)
        assertEquals(0, n.lateNight.spent.compareTo(BigDecimal("1000")))
        assertEquals(0, n.weekdays.single { it.day == java.time.DayOfWeek.TUESDAY }.spent.compareTo(BigDecimal("1000")))
        assertEquals(0, r.total.spent.compareTo(BigDecimal("1500")), "₹1,000 share + ₹500 Zepto")
        val merchant = r.lenses.single { it.lens.id == nightsOut }.merchants.single()
        assertEquals("Toit", merchant.merchant)
        assertEquals(0, merchant.numbers.spent.compareTo(BigDecimal("1000")))
    }

    @Test
    fun `the drill-down lists the split bill once and no settlement, so the number and its list agree`() = runBlocking<Unit> {
        val k = CoachKit().scenario()
        val week = DateRange(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 18))
        val list = k.leaks.transactions(k.user, week, IST, LeakFilter(lens = nightsOut), limit = 50)
        assertEquals(1, list.total)
        val bill = list.items.single()
        assertEquals(BigDecimal("4000.00"), bill.amount, "the list shows the bank's amount …")
        assertEquals(BigDecimal("1000.00"), bill.countedAmount, "… and what it counts as, which the number sums")
        assertTrue(k.leaks.facts(k.user, week, IST).none { it.nature == TransactionNature.SETTLEMENT })
    }

    @Test
    fun `a leak fact counts exactly the transaction's counted amount, split or not`() {
        val at = nov(17)
        val whole = txn("Toit", "4000.00", at)
        val split = whole.copy(ownShare = BigDecimal("1000.00"))
        val nothing = whole.copy(ownShare = BigDecimal("0.00"))     // the owner paid only for friends
        for (t in listOf(whole, split, nothing)) {
            val f = LeakFact(t, setOf(nightsOut), at, timeKnown = true)
            assertEquals(t.countedAmount, f.amount)
            assertEquals(t.countedAmount, f.signed)
        }
        val refund = LeakFact(txn("Toit", "4000.00", at, nature = TransactionNature.REFUND).copy(ownShare = BigDecimal("1000.00")), setOf(nightsOut), at, true)
        assertEquals(BigDecimal("-1000.00"), refund.signed, "a refund of a split bill takes back only the share")
    }

    // ── Limits ──

    @Test
    fun `a limit and its push count the share, and settlements don't move it`() = runBlocking<Unit> {
        val k = learned().scenario()
        val limit = (k.limits.create(k.user, IST, LimitRequest(LimitScope.Lens(nightsOut), LimitPeriod.WEEKLY, BigDecimal("2000"))) as LimitOutcome.Saved).limit
        // Creating a limit arms it silently at what is already spent; a new split bill then crosses 80%.
        k.splitBill("2400.00", "600.00", nov(18, 11, 0), merchant = "Social")
        assertEquals(1, k.alerts.check(k.user))
        val status = k.limits.statuses(k.user, IST).single { it.limit.id == limit.id }
        assertEquals(0, status.spent.compareTo(BigDecimal("1600")), "₹1,000 + ₹600 shares, not ₹6,400 of bills")
        assertEquals(BigDecimal("0.8000"), status.used)
        val push = k.pushes().single()
        assertEquals("Nights out: 80% of your weekly limit", push.title)
        assertEquals("₹1,600 of ₹2,000 spent this week; ₹400 left, 4 days to go.", push.body)
        // Settling up with friends afterwards changes nothing.
        k.settlement("1800.00", nov(18, 11, 30), received = true, merchant = "Social")
        k.settlement("1800.00", nov(18, 11, 40), received = false, merchant = "Social")
        assertEquals(0, k.alerts.check(k.user))
        assertEquals(0, k.limits.statuses(k.user, IST).single().spent.compareTo(BigDecimal("1600")))
    }

    @Test
    fun `a split bill that would cross a limit at full price stays under it at the owner's share`() = runBlocking<Unit> {
        val k = learned()
        k.limits.create(k.user, IST, LimitRequest(LimitScope.Merchant("Toit"), LimitPeriod.MONTHLY, BigDecimal("3000")))
        k.splitBill("4000.00", "1000.00", nov(17, 21, 0))
        assertEquals(0, k.alerts.check(k.user), "₹1,000 of ₹3,000 is under 50%: no push")
        assertTrue(k.pushes().isEmpty())
    }

    @Test
    fun `the suggested limit is learned from shares, not whole bills`() = runBlocking<Unit> {
        val k = learned()
        for (d in listOf(20, 27)) k.splitBill("6000.00", "1500.00", LocalDateTime.of(2026, 10, d, 20, 0))
        for (d in listOf(3, 10)) k.splitBill("4000.00", "1000.00", nov(d, 20))
        k.settlement("9000.00", nov(11, 9), received = true)
        val s = k.limits.overview(k.user, IST).suggestions.single { it.lens.id == nightsOut }
        assertEquals(BigDecimal("1100.00"), s.amount) // median share 1250 − 10% = 1125 → 1100, as for unsplit spends
    }

    // ── Goals ──

    @Test
    fun `goal progress counts the share and a settlement neither spends nor breaks a streak`() = runBlocking<Unit> {
        val k = CoachKit()
        k.clock.at(nov(16, 9))
        k.goals.create(k.user, IST, GoalRequest("nights_out", GoalKind.CAP_AMOUNT, BigDecimal(1500), LimitPeriod.WEEKLY))
        k.splitBill("4000.00", "1000.00", nov(16, 21, 0))
        k.settlement("3000.00", nov(17, 10), received = true)
        k.settlement("450.00", nov(18, 10), received = false)
        k.clock.at(nov(18, 12))
        val p = k.goals.list(k.user, IST).single()
        assertEquals(0, p.currentPeriod.spent.compareTo(BigDecimal("1000")))
        assertTrue(p.currentPeriod.met, "₹1,000 of ₹1,500: met, though the bill was ₹4,000")
        assertEquals(2, p.streak.current, "no orders on the 17th and 18th; the settle-ups aren't orders")
    }

    @Test
    fun `a cut goal's baseline is frozen from shares`() = runBlocking<Unit> {
        val k = CoachKit()
        for (m in 8..10) for (d in listOf(5, 15, 25)) k.splitBill("4000.00", "1000.00", LocalDateTime.of(2026, m, d, 20, 0))
        for (m in 8..10) k.settlement("6000.00", LocalDateTime.of(2026, m, 26, 10, 0), received = true)
        val g = (k.goals.create(k.user, IST, GoalRequest("nights_out", GoalKind.CUT_PERCENT, BigDecimal(25), LimitPeriod.MONTHLY)) as GoalOutcome.Saved).progress.goal
        assertEquals(BigDecimal("3000.00"), g.baseline, "three ₹1,000 shares a month, not ₹12,000")
        assertEquals(BigDecimal("2250.00"), g.target)
    }

    // ── Savings ──

    @Test
    fun `the savings rate and what leaks could become count the share, and settlements are neither income nor spend`() = runBlocking<Unit> {
        val k = CoachKit()
        k.transactions.save(txn("Acme Payroll", "100000.00", LocalDateTime.of(2026, 10, 1, 10, 0), nature = TransactionNature.INCOME, user = k.user))
        k.transactions.save(txn("Rent", "60000.00", LocalDateTime.of(2026, 10, 5, 10, 0), user = k.user))
        k.splitBill("4000.00", "1000.00", LocalDateTime.of(2026, 10, 10, 22, 0))
        k.settlement("3000.00", LocalDateTime.of(2026, 10, 12, 10, 0), received = true)   // not income
        k.settlement("450.00", LocalDateTime.of(2026, 10, 13, 10, 0), received = false)   // not spend
        val r = k.savings.report(k.user, IST, 3, Assumptions.DEFAULT)
        val oct = r.months.single { it.month == YearMonth.of(2026, 10) }
        assertEquals(0, oct.income.compareTo(BigDecimal("100000")))
        assertEquals(0, oct.spent.compareTo(BigDecimal("61000")), "rent + the ₹1,000 share")
        assertEquals(BigDecimal("0.3900"), oct.rate)
        val p = r.projection!!
        assertEquals(YearMonth.of(2026, 10), p.month)
        assertEquals(0, p.monthlyAmount.compareTo(BigDecimal("1000")), "October's leaks are the share only")
    }

    @Test
    fun `a bill the owner paid only for friends counts nothing anywhere`() = runBlocking<Unit> {
        val k = learned()
        k.limits.create(k.user, IST, LimitRequest(LimitScope.Lens(nightsOut), LimitPeriod.WEEKLY, BigDecimal("500")))
        k.splitBill("4000.00", "0.00", nov(17, 21, 0))
        assertEquals(0, k.alerts.check(k.user))
        val week = DateRange(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 18))
        val n = k.leaks.report(k.user, week, IST).lenses.single { it.lens.id == nightsOut }.numbers
        assertEquals(0, n.spent.signum())
        assertEquals(1, n.orders, "still an order the owner placed, at ₹0 to them")
        assertIs<BigDecimal>(n.averageOrder)
        assertEquals(0, n.averageOrder!!.signum())
    }
}
