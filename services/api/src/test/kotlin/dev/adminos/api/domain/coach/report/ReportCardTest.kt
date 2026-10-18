package dev.adminos.api.domain.coach.report

import dev.adminos.api.domain.agent.InMemoryAnomalyRepository
import dev.adminos.api.domain.agent.InMemoryBriefingRepository
import dev.adminos.api.domain.agent.briefing.BriefingSources
import dev.adminos.api.domain.agent.briefing.WeeklyBriefingService
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.CoachFixture.IST
import dev.adminos.api.domain.coach.CoachFixture.txn
import dev.adminos.api.domain.coach.CoachKit
import dev.adminos.api.domain.coach.lenses.LensCatalogue
import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.coach.limits.LimitPeriod
import dev.adminos.api.domain.coach.limits.LimitRequest
import dev.adminos.api.domain.coach.limits.LimitScope
import dev.adminos.api.domain.financial.DuesService
import dev.adminos.api.domain.financial.InMemoryBillRepository
import dev.adminos.api.domain.financial.InMemorySubscriptionRepository
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository
import dev.adminos.api.domain.financial.mandates.MandateService
import dev.adminos.api.domain.notifications.NotificationPreferences
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AOS-906: the report card for Mon 9 – Sun 15 Nov 2026, sent with the Monday briefing (16 Nov, 08:00 India).
 * The 8 weeks before: quick commerce ₹1,000 and food delivery ₹800 each week.
 */
class ReportCardTest {
    private val week = DateRange(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15))

    private suspend fun CoachKit.spend(merchant: String, amount: String, at: LocalDateTime, sub: String, category: TransactionCategory) =
        transactions.save(txn(merchant, amount, at, category = category, subcategory = sub, user = user))

    private suspend fun scenario(): CoachKit {
        val k = CoachKit()
        k.clock.at(LocalDateTime.of(2026, 11, 16, 8, 0))
        for (w in ReportCard.windows(week)) {
            k.spend("Zepto", "500.00", w.from.atTime(18, 0), "groceries", TransactionCategory.SHOPPING)
            k.spend("Blinkit", "500.00", w.from.plusDays(3).atTime(18, 0), "groceries", TransactionCategory.SHOPPING)
            k.spend("Swiggy", "800.00", w.from.plusDays(4).atTime(20, 0), "food_delivery", TransactionCategory.FOOD)
        }
        // This week: 9 quick-commerce orders (₹410 each), 5 after 22:00; one small Swiggy order.
        for (i in 0 until 9) k.spend("Zepto", "410.00", week.from.plusDays((i % 7).toLong()).atTime(if (i < 5) 23 else 13, 0), "groceries", TransactionCategory.SHOPPING)
        k.spend("Swiggy", "300.00", week.from.plusDays(5).atTime(13, 0), "food_delivery", TransactionCategory.FOOD)
        k.limits.create(k.user, IST, LimitRequest(LimitScope.Lens(LensId("food_delivery")), LimitPeriod.WEEKLY, BigDecimal("1000")))
        return k
    }

    private fun card(k: CoachKit) = ReportCard(k.analytics, k.leaks, k.limits)

    @Test
    fun `the week against the usual week, best and worst lens, limit outcomes and one concrete suggestion`() = runBlocking<Unit> {
        val k = scenario()
        val out = card(k).render(k.user, week, IST)
        assertEquals("reportCard", out.key)
        assertEquals(listOf(
            "Report card: ₹3,990 spent, ₹2,190 more than your usual ₹1,800.",
            "Best: Food delivery, ₹500 below usual. Worst: Quick commerce, ₹2,690 above usual.",
            "Limits: Food delivery kept (₹300 of ₹1,000 weekly).",
            "A suggested limit is ready on the leaks page.",
            "Try this: Quick commerce: 9 orders, avg ₹410, 5 after 22:00. A ₹900 weekly limit would have saved ₹2,790."
        ), out.lines)
        assertEquals("3990.00", out.structured["spent"]); assertEquals("1800.00", out.structured["usual"])
        assertEquals("quick_commerce", out.structured["worst"]); assertEquals(true, out.structured["suggestedLimitReady"])
    }

    @Test
    fun `an accepted weekly limit is the cap the suggestion uses, and an over limit says by how much`() = runBlocking<Unit> {
        val k = scenario()
        k.limits.create(k.user, IST, LimitRequest(LimitScope.Lens(LensId("quick_commerce")), LimitPeriod.WEEKLY, BigDecimal("2000")))
        val out = card(k).render(k.user, week, IST)
        assertTrue("Limits: Food delivery kept (₹300 of ₹1,000 weekly); Quick commerce over by ₹1,690 (₹2,000 weekly)." in out.lines, out.lines.toString())
        assertEquals("Try this: Quick commerce: 9 orders, avg ₹410, 5 after 22:00. A ₹2,000 weekly limit would have saved ₹1,690.", out.lines.last())
    }

    @Test
    fun `a new user has no usual week yet and still gets exactly one suggestion`() = runBlocking<Unit> {
        val k = CoachKit()
        k.clock.at(LocalDateTime.of(2026, 11, 16, 8, 0))
        k.spend("Zepto", "200.00", week.from.atTime(12, 0), "groceries", TransactionCategory.SHOPPING)
        val out = card(k).render(k.user, week, IST)
        assertEquals("Report card: ₹200 spent; not enough weeks yet for your usual week.", out.lines.first())
        assertEquals("Try this: Habits cost ₹200 this week. Pick one lens on the leaks page and set a limit for it.", out.lines.last())
        assertEquals(1, out.lines.count { it.startsWith("Try this:") })
        assertNull(card(k).facts(k.user, week, IST).best)
    }

    @Test
    fun `a report card covers seven days`() = runBlocking<Unit> {
        val k = CoachKit()
        assertFailsWith<IllegalArgumentException> { card(k).facts(k.user, DateRange(week.from, week.from), IST) }
    }

    @Test
    fun `it rides on the weekly briefing as one notification, with its structured copy`() = runBlocking<Unit> {
        val k = scenario()
        val subscriptions = InMemorySubscriptionRepository()
        val mandates = MandateService(InMemoryMandateRepository(), k.transactions)
        val briefings = InMemoryBriefingRepository()
        val service = WeeklyBriefingService(
            BriefingSources(k.analytics, DuesService(InMemoryBillRepository(), subscriptions, mandates), InMemoryAnomalyRepository(), subscriptions, mandates),
            briefings, k.notifications,
            dev.adminos.api.domain.agent.briefing.BriefingNarrator(dev.adminos.api.domain.agent.briefing.NarrationFixture.gatewayWithNoRoutes(k.clock)), k.clock, sections = listOf(card(k))
        )
        val made = service.briefIfDue(k.owner, NotificationPreferences(userId = k.user))
        val content = made?.content.orEmpty()
        assertTrue(content.startsWith("Last week (9 Nov–15 Nov) you spent ₹3,990"), content)
        assertTrue(content.contains("\nTry this: Quick commerce: 9 orders"), content)
        assertEquals("weekly-v2", made?.promptVersion)
        assertTrue(made?.contentStructured?.containsKey("reportCard") == true)
        assertEquals(1, k.pushes(dev.adminos.api.domain.notifications.NotificationType.BRIEFING).size)
    }

    private fun facts(lenses: List<LensWeek>, usual: BigDecimal? = null) = ReportCardFacts(week, lenses.fold(BigDecimal.ZERO) { a, l -> a + l.spent }, usual, lenses, emptyList(), false)
    private val qc = LensCatalogue.DEFAULT.byId(LensId("quick_commerce"))!!
    private val food = LensCatalogue.DEFAULT.byId(LensId("food_delivery"))!!
    private fun lw(lens: dev.adminos.api.domain.coach.lenses.HabitLens, spent: String, orders: Int, late: Int = 0, lateSpent: String = "0", usual: String? = null) =
        LensWeek(lens, BigDecimal(spent), orders, BigDecimal(spent), late, BigDecimal(lateSpent), usual?.let(::BigDecimal))

    @Test
    fun `suggestion rules apply in order and each is backed by numbers`() {
        // Late night wins when no cap can be worked out (no usual, no limit).
        val late = facts(listOf(lw(qc, "1200", 4, late = 3, lateSpent = "900")))
        assertEquals("3 orders after 22:00 came to ₹900. Skipping them would have saved ₹900.", Suggestions.pick(late))
        // Below usual by at least 10%.
        val under = facts(listOf(lw(qc, "500", 1, usual = "1000"), lw(food, "400", 1, usual = "600")))
        assertEquals("Habits cost ₹900 this week, ₹700 under your usual ₹1,600. Same again next week puts ₹2,800 a month in your pocket.", Suggestions.pick(under))
        // Nothing at all.
        assertEquals("No habit spending this week. Keep the streak going.", Suggestions.pick(facts(listOf(lw(qc, "0", 0, usual = "0")))))
        // Would-have-saved needs 3 orders and spending above the cap.
        assertNull(Suggestions.wouldHaveSaved.suggest(facts(listOf(lw(qc, "5000", 2, usual = "1000")))))
        assertNull(Suggestions.wouldHaveSaved.suggest(facts(listOf(lw(qc, "800", 4, usual = "1000")))))
        assertNull(Suggestions.wouldHaveSaved.suggest(facts(listOf(lw(qc, "800", 4, usual = "50")))), "a cap below ₹100 isn't offered")
        assertNull(Suggestions.belowUsual.suggest(facts(listOf(lw(qc, "950", 1, usual = "1000")))), "5% under isn't news")
        assertNull(Suggestions.belowUsual.suggest(facts(listOf(lw(qc, "0", 0, usual = "0")))))
    }

    @Test
    fun `best and worst need a usual week, and the usual needs two weeks with spending`() {
        assertNull(ReportCard.usualOf(listOf(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("700"))))
        assertEquals(BigDecimal("0.00"), ReportCard.usualOf(listOf(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("700"), BigDecimal("100"), BigDecimal.ZERO)))
        val f = facts(listOf(lw(qc, "1200", 3), lw(food, "100", 1, usual = "100")))
        assertNull(f.best); assertNull(f.worst)
        assertEquals(8, ReportCard.windows(week).size)
        assertEquals(LocalDate.of(2026, 9, 14), ReportCard.windows(week).first().from)
    }
}
