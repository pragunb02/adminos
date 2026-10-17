package dev.adminos.api.domain.coach.limits

import dev.adminos.api.domain.coach.CoachFixture.IST
import dev.adminos.api.domain.coach.CoachFixture.txn
import dev.adminos.api.domain.coach.CoachKit
import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.notifications.NotificationType
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * AOS-904: limits, the learning month, suggestions and the 50/80/100% pushes. "Now" is Wed 18 Nov 2026,
 * 12:00 in India; this week is Mon 16 – Sun 22 Nov.
 */
class LimitServiceTest {
    private val qc = LimitScope.Lens(LensId("quick_commerce"))
    private fun nov(d: Int, h: Int = 12, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m)

    private suspend fun CoachKit.spend(merchant: String, amount: String, at: LocalDateTime, nature: TransactionNature = TransactionNature.SPEND) =
        transactions.save(txn(merchant, amount, at, nature = nature, category = TransactionCategory.SHOPPING, subcategory = "groceries", user = user))

    private suspend fun CoachKit.weeklyQc(amount: String = "1000.00"): SpendingLimit =
        (limits.create(user, IST, LimitRequest(qc, LimitPeriod.WEEKLY, BigDecimal(amount))) as LimitOutcome.Saved).limit

    private fun kit(learned: Boolean = true) = CoachKit().also { k -> if (learned) runBlocking { k.history(LocalDateTime.of(2026, 10, 1, 9, 0)) } }

    @Test
    fun `during the learning month no limit can be set and nothing is suggested`() = runBlocking<Unit> {
        val k = kit(learned = false)
        k.history(nov(1))
        val out = k.limits.create(k.user, IST, LimitRequest(qc, LimitPeriod.WEEKLY, BigDecimal("1000")))
        assertIs<LimitOutcome.StillLearning>(out)
        assertEquals("Learning your habits: 18 of 30 days", out.learning.label)
        val o = k.limits.overview(k.user, IST)
        assertFalse(o.learning.ready); assertTrue(o.suggestions.isEmpty()); assertTrue(o.statuses.isEmpty())
    }

    @Test
    fun `no data at all is day zero of learning`() = runBlocking<Unit> {
        assertEquals(0, kit(learned = false).limits.overview(CoachKit().user, IST).learning.daysOfData)
    }

    @Test
    fun `after the learning month a suggestion appears, accepting it makes a limit, and the suggestion goes`() = runBlocking<Unit> {
        val k = kit()
        for (d in listOf(20, 27)) k.spend("Zepto", "1500.00", LocalDateTime.of(2026, 10, d, 20, 0))
        for (d in listOf(3, 10)) k.spend("Zepto", "1000.00", nov(d, 20))
        val s = k.limits.overview(k.user, IST).suggestions.single()
        assertEquals("quick_commerce", s.lens.id.value)
        assertEquals(BigDecimal("1100.00"), s.amount) // median 1250 − 10% = 1125 → 1100
        val accepted = k.limits.create(k.user, IST, LimitRequest(LimitScope.Lens(s.lens.id), s.period, s.amount))
        assertIs<LimitOutcome.Saved>(accepted)
        val after = k.limits.overview(k.user, IST)
        assertTrue(after.suggestions.isEmpty())
        assertEquals("Quick commerce", after.statuses.single().limit.label)
    }

    @Test
    fun `not now hides a suggestion for thirty days`() = runBlocking<Unit> {
        val k = kit()
        for (d in listOf(20, 27)) k.spend("Zepto", "1500.00", LocalDateTime.of(2026, 10, d, 20, 0))
        assertTrue(k.limits.dismiss(k.user, "quick_commerce"))
        assertFalse(k.limits.dismiss(k.user, "gambling"))
        assertFalse(k.limits.dismiss(k.user, "../x"))
        assertTrue(k.limits.overview(k.user, IST).suggestions.isEmpty())
    }

    @Test
    fun `one limit per scope and period, labels per scope, and only the owner can change or delete it`() = runBlocking<Unit> {
        val k = kit()
        val l = k.weeklyQc()
        assertIs<LimitOutcome.Duplicate>(k.limits.create(k.user, IST, LimitRequest(qc, LimitPeriod.WEEKLY, BigDecimal("5"))))
        val monthly = k.limits.create(k.user, IST, LimitRequest(qc, LimitPeriod.MONTHLY, BigDecimal("4000")))
        assertIs<LimitOutcome.Saved>(monthly)
        assertIs<LimitOutcome.Duplicate>(k.limits.update(k.user, IST, monthly.limit.id, null, LimitPeriod.WEEKLY))
        assertEquals("Zepto", (k.limits.create(k.user, IST, LimitRequest(LimitScope.Merchant(" Zepto "), LimitPeriod.WEEKLY, BigDecimal("500"))) as LimitOutcome.Saved).limit.label)
        assertEquals("EMI", (k.limits.create(k.user, IST, LimitRequest(LimitScope.Category(TransactionCategory.EMI), LimitPeriod.MONTHLY, BigDecimal("500"))) as LimitOutcome.Saved).limit.label)
        assertEquals("Food", (k.limits.create(k.user, IST, LimitRequest(LimitScope.Category(TransactionCategory.FOOD), LimitPeriod.MONTHLY, BigDecimal("500"))) as LimitOutcome.Saved).limit.label)
        assertIs<LimitOutcome.UnknownLens>(k.limits.create(k.user, IST, LimitRequest(LimitScope.Lens(LensId("gambling")), LimitPeriod.WEEKLY, BigDecimal("5"))))

        val stranger = UUID.randomUUID()
        assertEquals(LimitOutcome.NotFound, k.limits.update(stranger, IST, l.id, BigDecimal("1"), null))
        assertFalse(k.limits.delete(stranger, l.id))
        val raised = k.limits.update(k.user, IST, l.id, BigDecimal("2000"), null)
        assertEquals(BigDecimal("2000"), (raised as LimitOutcome.Saved).limit.amount)
        assertTrue(k.limits.delete(k.user, l.id))
        assertFalse(k.limits.delete(k.user, l.id))
    }

    @Test
    fun `crossing 50, 80 and 100 percent pushes once each`() = runBlocking<Unit> {
        val k = kit()
        k.weeklyQc()
        k.spend("Zepto", "550.00", nov(16))
        assertEquals(1, k.alerts.check(k.user))
        assertEquals(0, k.alerts.check(k.user), "the same threshold never twice")
        k.clock.advance(Duration.ofMinutes(1))
        k.spend("Blinkit", "300.00", nov(17))
        assertEquals(1, k.alerts.check(k.user))
        k.clock.advance(Duration.ofMinutes(1))
        k.spend("Zepto", "200.00", nov(18))
        assertEquals(1, k.alerts.check(k.user))
        assertEquals(0, k.alerts.check(k.user))
        val pushes = k.pushes()
        assertEquals(listOf("Quick commerce: 50% of your weekly limit", "Quick commerce: 80% of your weekly limit", "Quick commerce: weekly limit reached"), pushes.map { it.title })
        assertEquals("₹1,050 of ₹1,000 spent this week; ₹50 over, 4 days to go.", pushes.last().body)
        assertEquals("coach", pushes.last().data?.get("entity_type"))
    }

    @Test
    fun `several thresholds at once give one push, for the highest`() = runBlocking<Unit> {
        val k = kit()
        val l = k.weeklyQc()
        k.spend("Zepto", "1200.00", nov(17))
        assertEquals(1, k.alerts.check(k.user))
        assertEquals(listOf("Quick commerce: weekly limit reached"), k.pushes().map { it.title })
        assertEquals(Threshold.entries.toSet(), k.ledger.claimed(l.id, l.period.rangeOf(nov(18).toLocalDate()).from))
    }

    @Test
    fun `a refund below a threshold doesn't re-arm it, and a new week starts fresh`() = runBlocking<Unit> {
        val k = kit()
        k.weeklyQc()
        k.spend("Zepto", "600.00", nov(16))
        assertEquals(1, k.alerts.check(k.user))
        k.spend("Zepto", "300.00", nov(17), TransactionNature.REFUND)
        k.spend("Zepto", "250.00", nov(18))
        assertEquals(0, k.alerts.check(k.user), "back over 50% after a refund: already announced this week")
        k.clock.at(nov(23, 12)) // Monday 23 Nov
        k.spend("Zepto", "520.00", nov(23, 11))
        assertEquals(1, k.alerts.check(k.user))
        assertEquals(2, k.pushes().size)
    }

    @Test
    fun `setting a limit already passed records it silently`() = runBlocking<Unit> {
        val k = kit()
        k.spend("Zepto", "900.00", nov(16))
        k.weeklyQc()
        assertEquals(0, k.alerts.check(k.user))
        k.spend("Zepto", "150.00", nov(17))
        assertEquals(1, k.alerts.check(k.user))
        assertEquals("Quick commerce: weekly limit reached", k.pushes().single().title)
    }

    @Test
    fun `with alerts off thresholds pass silently and turning them on releases no backlog`() = runBlocking<Unit> {
        val k = kit()
        k.weeklyQc()
        k.settings.update(k.user) { it.copy(limitAlerts = false) }
        k.spend("Zepto", "900.00", nov(16))
        assertEquals(0, k.alerts.check(k.user))
        k.settings.update(k.user) { it.copy(limitAlerts = true) }
        assertEquals(0, k.alerts.check(k.user))
        k.spend("Zepto", "200.00", nov(17))
        assertEquals(1, k.alerts.check(k.user))
    }

    @Test
    fun `a monthly limit counts the calendar month in the user's zone`() = runBlocking<Unit> {
        val k = kit()
        k.limits.create(k.user, IST, LimitRequest(qc, LimitPeriod.MONTHLY, BigDecimal("1000")))
        // 31 Oct 19:00 UTC is 1 Nov 00:30 in India: in November.
        k.transactions.save(txn("Zepto", "600.00", LocalDateTime.of(2026, 10, 31, 19, 0), category = TransactionCategory.SHOPPING, zone = java.time.ZoneOffset.UTC, user = k.user))
        k.transactions.save(txn("Zepto", "5000.00", LocalDateTime.of(2026, 10, 31, 18, 0), category = TransactionCategory.SHOPPING, zone = java.time.ZoneOffset.UTC, user = k.user))
        val s = k.limits.statuses(k.user, IST).single()
        assertEquals(0, s.spent.compareTo(BigDecimal("600")))
        assertEquals(12, s.daysLeft)
        assertEquals("₹600 of ₹1,000 spent this month; ₹400 left, 12 days to go.", LimitAlertWriter.body(s))
    }

    @Test
    fun `the push text reads right on the last days`() {
        val l = SpendingLimit(userId = UUID.randomUUID(), scope = qc, label = "Quick commerce", period = LimitPeriod.WEEKLY, amount = BigDecimal("1000"),
            createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH)
        val week = l.period.rangeOf(nov(16).toLocalDate())
        assertEquals("₹500 of ₹1,000 spent this week; ₹500 left, last day.", LimitAlertWriter.body(LimitStatus(l, week, nov(22).toLocalDate(), BigDecimal("500"))))
        assertEquals("₹500 of ₹1,000 spent this week; ₹500 left, 1 day to go.", LimitAlertWriter.body(LimitStatus(l, week, nov(21).toLocalDate(), BigDecimal("500"))))
    }

    @Test
    fun `the listener checks only after spending or refunds, and the job checks every active user`() = runBlocking<Unit> {
        val k = kit()
        k.weeklyQc()
        val big = txn("Zepto", "900.00", nov(16), category = TransactionCategory.SHOPPING, user = k.user)
        k.transactions.save(big)
        LimitAlertListener(k.alerts).onCreated(big.copy(nature = TransactionNature.TRANSFER))
        assertEquals(0, k.pushes().size)
        LimitAlertListener(k.alerts).onCreated(big)
        assertEquals(1, k.pushes().size)
        k.spend("Zepto", "200.00", nov(17))
        val job = LimitAlertsJob(k.users, k.alerts)
        assertEquals("coach-limit-alerts", job.name.value)
        assertEquals(Duration.ofMinutes(10), (job.schedule as dev.adminos.api.domain.scheduling.Schedule.Every).period)
        val result = job.run()
        assertEquals(1, result.counts["alertsSent"]); assertEquals(0, result.counts["failed"])
        assertEquals(NotificationType.COACH, k.pushes().last().type)
    }
}
