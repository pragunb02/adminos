package dev.adminos.api.domain.coach.digest

import dev.adminos.api.domain.coach.CoachFixture.IST
import dev.adminos.api.domain.coach.CoachFixture.txn
import dev.adminos.api.domain.coach.CoachKit
import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.coach.limits.LimitPeriod
import dev.adminos.api.domain.coach.limits.LimitRequest
import dev.adminos.api.domain.coach.limits.LimitScope
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-905: one digest an evening at the owner's time, with today's facts. "Today" is Wed 18 Nov 2026 in India. */
class EveningDigestTest {
    private fun nov(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m)

    private suspend fun CoachKit.spend(merchant: String, amount: String, at: LocalDateTime, subcategory: String? = null,
                                       category: TransactionCategory = TransactionCategory.SHOPPING, nature: TransactionNature = TransactionNature.SPEND, vpa: String? = null) =
        transactions.save(txn(merchant, amount, at, nature = nature, category = category, subcategory = subcategory, vpa = vpa, user = user))

    @Test
    fun `before the chosen time nothing, at it one digest with today's facts, then never again that day`() = runBlocking<Unit> {
        val k = CoachKit()
        k.history(LocalDateTime.of(2026, 10, 1, 9, 0))
        k.limits.create(k.user, IST, LimitRequest(LimitScope.Lens(LensId("quick_commerce")), LimitPeriod.WEEKLY, BigDecimal("2000")))
        k.spend("Zepto", "1000.00", nov(16, 20))                      // earlier this week: counts for the limit, not today
        k.spend("Zepto", "600.00", nov(18, 0, 30))                    // today, late night
        k.spend("Swiggy", "350.00", nov(18, 13), "food_delivery", TransactionCategory.FOOD)
        k.spend("Rohit Sharma", "500.00", nov(18, 14), vpa = "rohit.sharma92@oksbi") // a settle-up: in today's total, not a leak
        k.now(nov(18, 21, 29))
        assertFalse(k.digests.digestIfDue(k.owner))
        k.now(nov(18, 21, 30))
        assertTrue(k.digests.digestIfDue(k.owner))
        val push = k.pushes().single()
        assertEquals("Today: ₹1,450 spent", push.title)
        assertEquals("Leaks: Quick commerce ₹600 (1 order, 1 late), Food delivery ₹350 (1 order). Limits: Quick commerce 80% of ₹2,000 this week.", push.body)
        assertEquals("digest", push.data?.get("action"))
        k.now(nov(18, 23, 0))
        assertFalse(k.digests.digestIfDue(k.owner))
        assertEquals(1, k.pushes().size)
    }

    @Test
    fun `a missed evening is not sent the next morning, and the next evening sends again`() = runBlocking<Unit> {
        val k = CoachKit()
        k.now(nov(19, 9, 0))
        assertFalse(k.digests.digestIfDue(k.owner))
        k.now(nov(19, 21, 45))
        assertTrue(k.digests.digestIfDue(k.owner))
    }

    @Test
    fun `a zero day says so, or is skipped until something is spent`() = runBlocking<Unit> {
        val k = CoachKit()
        k.now(nov(18, 22, 0))
        assertTrue(k.digests.digestIfDue(k.owner))
        assertEquals("Today: nothing spent", k.pushes().single().title)
        assertEquals("No spending recorded today.", k.pushes().single().body)

        val s = CoachKit()
        s.settings.update(s.user) { it.copy(digestSkipZeroDay = true) }
        s.now(nov(18, 22, 0))
        assertFalse(s.digests.digestIfDue(s.owner))
        s.spend("Chai Point", "40.00", nov(18, 22, 5), category = TransactionCategory.FOOD)
        s.now(nov(18, 22, 15))
        assertTrue(s.digests.digestIfDue(s.owner))
        assertEquals("1 payment, none on your habits.", s.pushes().single().body)
    }

    @Test
    fun `off sends nothing, and the time is the owner's in their own zone`() = runBlocking<Unit> {
        val k = CoachKit()
        k.settings.update(k.user) { it.copy(eveningDigest = false) }
        k.now(nov(18, 23, 0))
        assertFalse(k.digests.digestIfDue(k.owner))

        val ny = CoachKit()
        ny.users.update(ny.owner.copy(timezone = "America/New_York"))
        ny.settings.update(ny.user) { it.copy(digestTime = LocalTime.of(20, 0)) }
        ny.clock.at(LocalDateTime.of(2026, 11, 1, 19, 59), ZoneId.of("America/New_York")) // the day clocks go back
        assertFalse(ny.digests.digestIfDue(ny.owner))
        ny.clock.advance(Duration.ofMinutes(1))
        assertTrue(ny.digests.digestIfDue(ny.owner))
    }

    @Test
    fun `refunds net off and the writer handles plurals`() = runBlocking<Unit> {
        val k = CoachKit()
        k.spend("Zepto", "300.00", nov(18, 12), "groceries")
        k.spend("Zepto", "200.00", nov(18, 13), "groceries")
        k.spend("Zepto", "100.00", nov(18, 15), "groceries", nature = TransactionNature.REFUND)
        val f = k.digests.gather(k.user, nov(18, 0).toLocalDate())
        assertEquals(0, f.spent.compareTo(BigDecimal("400")))
        assertEquals("Leaks: Quick commerce ₹400 (2 orders).", DigestWriter.body(f))
    }

    @Test
    fun `the job sends due digests for every active user and counts failures`() = runBlocking<Unit> {
        val k = CoachKit()
        k.now(nov(18, 21, 40))
        val job = EveningDigestJob(k.users, k.digests)
        assertEquals("coach-evening-digest", job.name.value)
        assertEquals(1, job.run().counts["digestsSent"])
        assertEquals(0, job.run().counts["digestsSent"])
    }
}
