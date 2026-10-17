package dev.adminos.api.domain.financial

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The waste score's factors, threshold and flag rules (AOS-509). */
class WasteScorerTest {
    private val userId = UUID.randomUUID()
    private val today = LocalDate.of(2026, 10, 17)
    private val now = Instant.parse("2026-10-16T23:30:00Z")

    private fun sub(
        name: String = "Netflix", amount: String = "649.00", cycle: BillingCycle = BillingCycle.MONTHLY,
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE, usage: UsageStatus = UsageStatus.UNKNOWN,
        priceChanged: Boolean = false, pct: String? = null, firstBilled: LocalDate? = LocalDate.of(2026, 7, 25),
        next: LocalDate? = LocalDate.of(2026, 10, 25), category: SubscriptionCategory = SubscriptionCategory.OTHER
    ) = Subscription(
        userId = userId, name = name, merchantName = name.lowercase(), amount = BigDecimal(amount), billingCycle = cycle,
        status = status, usageStatus = usage, priceChanged = priceChanged, priceChangePct = pct?.let(::BigDecimal),
        firstBilledDate = firstBilled, nextBillingDate = next, category = category
    )

    private fun context(vararg subs: Subscription, spend: String? = null, dormant: List<String> = emptyList()) =
        WasteContext(today, subs.toList(), spend?.let(::BigDecimal), dormant)

    private fun score(s: Subscription, ctx: WasteContext = context(s)) = WasteScorer.score(s, ctx)

    private fun WasteAssessment.points(f: WasteFactor) = reasons.single { it.factor == f }.points

    // ── Factors ──

    @Test
    fun `nothing suspicious scores zero and says usage is unknown`() {
        val a = score(sub())
        assertEquals(BigDecimal("0.00"), a.score)
        assertEquals(listOf("Usage unknown: AdminOS can't see whether you use it"), a.reasons.map { it.reason })
        assertTrue(a.factors.isEmpty(), "the usage note adds no points")
    }

    @Test
    fun `usage the user confirmed is not called unknown`() {
        assertTrue(score(sub(usage = UsageStatus.ACTIVE)).reasons.isEmpty())
    }

    @Test
    fun `a price rise adds points and a big rise adds more`() {
        val small = score(sub(priceChanged = true, pct = "12.50"))
        assertEquals(WasteScorer.PRICE_INCREASED_POINTS, small.points(WasteFactor.PRICE_INCREASED))
        assertEquals("Price went up 12.5% at the last charge", small.reasons.first().reason)
        val big = score(sub(priceChanged = true, pct = "25.00"))
        assertEquals(WasteScorer.PRICE_INCREASED_POINTS + WasteScorer.BIG_PRICE_RISE_POINTS, big.points(WasteFactor.PRICE_INCREASED))
        assertEquals("Price went up 25% at the last charge", big.reasons.first().reason)
    }

    @Test
    fun `a price change without a percentage counts as a rise, a price cut does not`() {
        assertEquals("Price went up at the last charge", score(sub(priceChanged = true)).reasons.first().reason)
        assertFalse(WasteFactor.PRICE_INCREASED in score(sub(priceChanged = true, pct = "-10.00")).factors)
    }

    @Test
    fun `two streaming services are duplicates, from the merchant catalogue`() {
        val netflix = sub()
        val hotstar = sub(name = "JioHotstar", amount = "299.00")
        val spotify = sub(name = "Spotify", amount = "119.00")
        val a = score(netflix, context(netflix, hotstar, spotify))
        assertEquals("You also pay for JioHotstar (both streaming)", a.reasons.first().reason)
        assertEquals(WasteScorer.DUPLICATE_SERVICE_POINTS, a.points(WasteFactor.DUPLICATE_SERVICE))
        assertFalse(WasteFactor.DUPLICATE_SERVICE in score(spotify, context(netflix, hotstar, spotify)).factors, "music is another kind")
    }

    @Test
    fun `broad app stores and unknown merchants are never called duplicates`() {
        val apple = sub(name = "Apple")
        val openAi = sub(name = "OpenAI")
        assertFalse(WasteFactor.DUPLICATE_SERVICE in score(apple, context(apple, openAi)).factors)
        val a = sub(name = "Local Gym A")
        val b = sub(name = "Local Gym B")
        assertFalse(WasteFactor.DUPLICATE_SERVICE in score(a, context(a, b)).factors)
    }

    @Test
    fun `a set category groups subscriptions the catalogue doesn't know`() {
        val a = sub(name = "Gym Alpha", category = SubscriptionCategory.FITNESS)
        val b = sub(name = "Yoga Beta", category = SubscriptionCategory.FITNESS)
        assertEquals("You also pay for Yoga Beta (both fitness)", score(a, context(a, b)).reasons.first().reason)
    }

    @Test
    fun `a big slice of monthly spending adds points, using the monthly cost of any cycle`() {
        val yearly = sub(name = "Audible", amount = "12000.00", cycle = BillingCycle.YEARLY)
        val a = score(yearly, context(yearly, spend = "18000.00"))
        assertEquals("₹1,000 a month is 6% of your spending in the last 30 days", a.reasons.first().reason)
        assertEquals(WasteScorer.HIGH_COST_SHARE_POINTS, a.points(WasteFactor.HIGH_COST_SHARE))
        assertFalse(WasteFactor.HIGH_COST_SHARE in score(sub(), context(sub(), spend = "48250.00")).factors, "₹649 of ₹48,250 is about 1%")
    }

    @Test
    fun `unknown spending is no cost-share factor`() {
        assertFalse(WasteFactor.HIGH_COST_SHARE in score(sub(amount = "5000.00"), context(spend = null)).factors)
        assertFalse(WasteFactor.HIGH_COST_SHARE in score(sub(amount = "5000.00"), context(spend = "0")).factors)
    }

    @Test
    fun `paying a year or more with no change asks for a fresh look`() {
        val a = score(sub(firstBilled = LocalDate.of(2025, 8, 1)))
        assertEquals("Paying for 14 months without a change: worth a fresh look", a.reasons.first().reason)
        assertFalse(WasteFactor.LONG_RUNNING in score(sub(firstBilled = LocalDate.of(2025, 11, 1))).factors, "under a year")
        assertFalse(WasteFactor.LONG_RUNNING in score(sub(firstBilled = null)).factors, "start unknown")
        assertFalse(WasteFactor.LONG_RUNNING in score(sub(firstBilled = LocalDate.of(2024, 1, 1), priceChanged = true, pct = "10")).factors,
            "a price change is a change")
    }

    @Test
    fun `a dormant AutoPay for the same payee adds points`() {
        val a = score(sub(), context(sub(), dormant = listOf("netflix")))
        assertEquals("AutoPay for it is still active but nothing was taken for two cycles", a.reasons.first().reason)
        assertFalse(WasteFactor.DORMANT_MANDATE in score(sub(), context(sub(), dormant = listOf("bajaj finance"))).factors)
    }

    @Test
    fun `a trial turning paid within a week adds points`() {
        fun trial(next: LocalDate?) = score(sub(status = SubscriptionStatus.TRIAL, next = next))
        assertEquals("Free trial turns paid today", trial(today).reasons.first().reason)
        assertEquals("Free trial turns paid tomorrow", trial(today.plusDays(1)).reasons.first().reason)
        assertEquals("Free trial turns paid on 2026-10-24", trial(today.plusDays(7)).reasons.first().reason)
        assertFalse(WasteFactor.TRIAL_ENDING in trial(today.plusDays(8)).factors)
        assertFalse(WasteFactor.TRIAL_ENDING in trial(today.minusDays(1)).factors)
        assertFalse(WasteFactor.TRIAL_ENDING in trial(null).factors)
        assertFalse(WasteFactor.TRIAL_ENDING in score(sub(next = today)).factors, "not a trial")
    }

    @Test
    fun `marked unused adds points`() {
        assertEquals(WasteScorer.MARKED_UNUSED_POINTS, score(sub(usage = UsageStatus.UNUSED)).points(WasteFactor.MARKED_UNUSED))
    }

    // ── Score ──

    @Test
    fun `reasons come strongest first, the usage note last, and the score is their sum`() {
        val netflix = sub(priceChanged = true, pct = "12.50")
        val hotstar = sub(name = "JioHotstar")
        val a = score(netflix, context(netflix, hotstar, dormant = listOf("netflix")))
        assertEquals(listOf(WasteFactor.DORMANT_MANDATE, WasteFactor.DUPLICATE_SERVICE, WasteFactor.PRICE_INCREASED, WasteFactor.USAGE_UNKNOWN),
            a.reasons.map { it.factor })
        assertEquals(BigDecimal("1.00"), a.score, "40 + 35 + 30 is capped at 100")
    }

    @Test
    fun `the same inputs always give the same score and sentences`() {
        val netflix = sub(priceChanged = true, pct = "25")
        val ctx = context(netflix, sub(name = "SonyLIV"), spend = "9000")
        assertEquals(score(netflix, ctx), score(netflix, ctx))
    }

    // ── Flag ──

    private fun assessed(vararg factors: Pair<WasteFactor, Int>) =
        WasteAssessment(BigDecimal(factors.sumOf { it.second }.coerceAtMost(100)).movePointLeft(2).setScale(2), factors.map { WasteReason(it.first, it.second, "because ${it.first.code}") })

    @Test
    fun `a score of 0_70 or more flags it with the top three reasons`() {
        val a = assessed(WasteFactor.TRIAL_ENDING to 40, WasteFactor.DUPLICATE_SERVICE to 35, WasteFactor.PRICE_INCREASED to 30, WasteFactor.LONG_RUNNING to 15)
        val flagged = WasteScorer.applyTo(sub(), a, now)
        assertTrue(flagged.isFlagged)
        assertEquals("because trial_ending; because duplicate_service; because price_increased", flagged.flaggedReason)
        assertEquals(now, flagged.flaggedAt)
        assertEquals(BigDecimal("1.00"), flagged.wasteScore)
        assertEquals(now, flagged.wasteScoreUpdatedAt)
        assertEquals(a.reasons, flagged.wasteReasons)
        assertTrue(WasteScorer.applyTo(sub(), assessed(WasteFactor.TRIAL_ENDING to 40, WasteFactor.PRICE_INCREASED to 30), now).isFlagged, "exactly 0.70")
    }

    @Test
    fun `below the threshold it is not flagged, and an old flag goes`() {
        val low = assessed(WasteFactor.DUPLICATE_SERVICE to 35, WasteFactor.PRICE_INCREASED to 30)
        val wasFlagged = sub().copy(isFlagged = true, flaggedReason = "old", flaggedAt = now.minusSeconds(86400))
        val s = WasteScorer.applyTo(wasFlagged, low, now)
        assertFalse(s.isFlagged)
        assertNull(s.flaggedReason)
        assertNull(s.flaggedAt)
        assertEquals(BigDecimal("0.65"), s.wasteScore)
    }

    @Test
    fun `scoring again keeps the first flag time, so re-running is harmless`() {
        val a = assessed(WasteFactor.TRIAL_ENDING to 40, WasteFactor.DUPLICATE_SERVICE to 35)
        val first = WasteScorer.applyTo(sub(), a, now)
        val again = WasteScorer.applyTo(first, a, now.plusSeconds(86400))
        assertEquals(first.flaggedAt, again.flaggedAt)
        assertEquals(first.copy(wasteScoreUpdatedAt = again.wasteScoreUpdatedAt), again)
    }

    @Test
    fun `a kept subscription stays unflagged until a new factor appears`() {
        val a = assessed(WasteFactor.TRIAL_ENDING to 40, WasteFactor.DUPLICATE_SERVICE to 35)
        val kept = WasteScorer.applyTo(sub(), a, now).keep(now.plusSeconds(60))
        assertEquals(setOf(WasteFactor.TRIAL_ENDING, WasteFactor.DUPLICATE_SERVICE), kept.wasteFactorsDismissed)
        assertEquals(UsageStatus.ACTIVE, kept.usageStatus)

        val sameReasons = WasteScorer.applyTo(kept, a, now.plusSeconds(86400))
        assertFalse(sameReasons.isFlagged, "same factors: the keep still holds")
        assertEquals(kept.flagDismissedAt, sameReasons.flagDismissedAt)

        val newReason = WasteScorer.applyTo(sameReasons, assessed(WasteFactor.TRIAL_ENDING to 40, WasteFactor.PRICE_INCREASED to 30), now.plusSeconds(172800))
        assertTrue(newReason.isFlagged, "a price rise is new since the keep")
        assertNull(newReason.flagDismissedAt, "the old keep no longer covers it")
    }

    @Test
    fun `keeping ignores the usage note when remembering what was seen`() {
        val s = sub().copy(wasteReasons = listOf(WasteReason(WasteFactor.PRICE_INCREASED, 30, "up"), WasteReason(WasteFactor.USAGE_UNKNOWN, 0, "unknown")))
        assertEquals(setOf(WasteFactor.PRICE_INCREASED), s.keep(now).wasteFactorsDismissed)
    }

    // ── Validation ──

    @Test
    fun `bad scores, points and codes are rejected`() {
        assertFailsWith<IllegalArgumentException> { WasteAssessment(BigDecimal("1.01"), emptyList()) }
        assertFailsWith<IllegalArgumentException> { WasteAssessment(BigDecimal("-0.01"), emptyList()) }
        assertFailsWith<IllegalArgumentException> { WasteReason(WasteFactor.LONG_RUNNING, 101, "x") }
        assertFailsWith<IllegalArgumentException> { WasteReason(WasteFactor.LONG_RUNNING, -1, "x") }
        assertFailsWith<IllegalArgumentException> { WasteReason(WasteFactor.LONG_RUNNING, 10, " ") }
        assertFailsWith<IllegalArgumentException> { WasteFactor.fromCode("vibes") }
        assertFailsWith<IllegalArgumentException> { sub().copy(wasteScore = BigDecimal("1.5")) }
        assertFailsWith<IllegalArgumentException> { sub().copy(wasteScore = BigDecimal("-0.1")) }
        WasteFactor.entries.forEach { assertEquals(it, WasteFactor.fromCode(it.code)) }
    }

    @Test
    fun `monthly cost covers every billing cycle`() {
        val x = BigDecimal("120.00")
        assertEquals(BigDecimal("3600.00"), BillingCycle.DAILY.monthlyCost(x))
        assertEquals(BigDecimal("519.6000"), BillingCycle.WEEKLY.monthlyCost(x))
        assertEquals(x, BillingCycle.MONTHLY.monthlyCost(x))
        assertEquals(BigDecimal("40.00"), BillingCycle.QUARTERLY.monthlyCost(x))
        assertEquals(BigDecimal("10.00"), BillingCycle.YEARLY.monthlyCost(x))
    }
}
