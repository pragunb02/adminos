package dev.adminos.api.domain.agent

import dev.adminos.api.domain.agent.baselines.MeanMultipleBaseline
import dev.adminos.api.domain.agent.baselines.MedianMadBaseline
import dev.adminos.api.domain.agent.baselines.ToleranceLevel
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-713: which baseline judges a spend, what counts as history, and what the alert says. */
class RobustAmountRuleTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val user = UUID.randomUUID()
    /** 12:00 IST on 10 March 2026. */
    private val now = Instant.parse("2026-03-10T06:30:00Z")
    private val rule = RobustAmountRule()

    private fun spend(
        amount: String, merchant: String? = "Swiggy", daysAgo: Long = 0, category: TransactionCategory = TransactionCategory.FOOD,
        currency: String = "INR", nature: TransactionNature = TransactionNature.SPEND, at: Instant = now.minus(daysAgo, ChronoUnit.DAYS)
    ) = Transaction(userId = user, type = TransactionType.DEBIT, nature = nature, amount = BigDecimal(amount), currency = currency,
        merchantName = merchant, category = category, transactedAt = at)

    private fun usual(merchant: String? = "Swiggy", vararg amounts: Int, category: TransactionCategory = TransactionCategory.FOOD) =
        amounts.mapIndexed { i, a -> spend("$a.00", merchant, daysAgo = i * 7L + 1, category = category) }

    private fun context(history: List<Transaction>, tolerance: ToleranceLevel = ToleranceLevel.DEFAULT) = AmountContext(history, ist, tolerance)

    @Test
    fun `the merchant's own pattern decides, even when its category would call it big`() {
        // Rent-like: ₹25,000 at the landlord every month; the category is mostly ₹300 meals.
        val history = usual("Landlord", 25000, 25000, 25000, 25000, 25000) + usual("Cafe", 300, 320, 280, 310, 290, 305)
        val j = assertNotNull(rule.judge(spend("25000.00", "Landlord"), context(history)))
        assertEquals(BaselineScope.MERCHANT, j.tier.scope)
        assertEquals(false, j.unusual)
        assertNull(rule.evaluate(spend("25000.00", "Landlord"), context(history)))
    }

    @Test
    fun `a merchant with one to four earlier payments is judged by 3x their mean, not by its category`() {
        // Flights next to cab rides: the category would call every flight unusual.
        val history = usual("Uber", 250, 300, 280, 260, 310, 240, category = TransactionCategory.TRANSPORT) +
            usual("MakeMyTrip", 6000, 7000, category = TransactionCategory.TRANSPORT)
        assertNull(rule.evaluate(spend("9000.00", "MakeMyTrip", category = TransactionCategory.TRANSPORT), context(history)))
        val result = assertNotNull(rule.evaluate(spend("25000.00", "MakeMyTrip", category = TransactionCategory.TRANSPORT), context(history)))
        assertEquals("₹25,000 is more than usual at MakeMyTrip: usually ₹6,250–₹6,750 (2 payments in 180 days)", result.reason)
        val one = assertNotNull(rule.evaluate(spend("4000.00", "New Place"), context(usual("New Place", 400))))
        assertEquals("₹4,000 is more than usual at New Place: usually ₹400 (1 payment in 180 days)", one.reason)
    }

    @Test
    fun `a first payment somewhere is compared with what other places of its kind usually cost`() {
        // Five food places; the chai stall paid daily counts once, as ₹40.
        val history = usual("Cafe", 300, 320, 280) + usual("Swiggy", 450, 470) + usual("Zomato", 380) +
            usual("Chai", 40, 40, 40, 40, 40, 40, 40, 40) + usual("Dosa Corner", 200)
        val result = assertNotNull(rule.evaluate(spend("4000.00", "New Place"), context(history)))
        assertEquals("₹4,000 is more than usual for Food: usually ₹200–₹380 (5 places in 180 days)", result.reason)
        assertEquals(RobustAmountRule.NAME, result.ruleName)
        assertEquals(0.7, result.confidence)
        // A ₹2,500 dinner is within what food places cost once the chai stall can't outvote them.
        assertNull(rule.evaluate(spend("2500.00", "New Place"), context(history + usual("Barbeque", 1800) + usual("Mainland China", 2200))))
    }

    @Test
    fun `a thin category falls back to today's 3x rule, and nothing at all says nothing`() {
        val thin = usual("Cafe", 400, 600, 500)
        val j = assertNotNull(rule.judge(spend("2400.00", "Barbeque"), context(thin)))
        assertEquals(30, j.tier.lookbackDays)
        assertTrue(j.unusual)
        assertNull(rule.judge(spend("2400.00", "Barbeque"), context(usual("Cafe", 400, 600))), "two spends: too little")
        assertNull(rule.evaluate(spend("90000.00", "Laptop"), context(emptyList())), "a first-ever spend has nothing to compare with")
    }

    @Test
    fun `a spend with no merchant skips the merchant tier`() {
        val history = usual(null, 300, 320, 280, 310, 290)
        val j = assertNotNull(rule.judge(spend("5000.00", merchant = " "), context(history)))
        assertEquals(BaselineScope.CATEGORY, j.tier.scope)
    }

    @Test
    fun `below 2,000 rupees nothing is unusual, and only spends are judged`() {
        val history = usual("Chai", 20, 25, 20, 30, 22)
        assertNull(rule.evaluate(spend("1999.99", "Chai"), context(history)))
        assertNotNull(rule.evaluate(spend("2000.00", "Chai"), context(history)))
        assertNull(rule.evaluate(spend("5000.00", "Chai", nature = TransactionNature.REFUND), context(history)))
    }

    @Test
    fun `refunds, reversals, transfers, other currencies, later spends and the spend itself never enter the baseline`() {
        val subject = spend("2400.00", "Zomato")
        val noise = listOf(
            spend("90000.00", "Zomato", daysAgo = 2, nature = TransactionNature.REFUND),
            spend("90000.00", "Zomato", daysAgo = 3, nature = TransactionNature.REVERSAL),
            spend("90000.00", "Zomato", daysAgo = 4, nature = TransactionNature.TRANSFER),
            spend("90000.00", "Zomato", daysAgo = 5, currency = "USD"),
            spend("0.00", "Zomato", daysAgo = 6),
            spend("90000.00", "Zomato", at = now.plusSeconds(60)),
            subject
        )
        val history = usual("Zomato", 400, 450, 500, 550, 600) + noise
        val j = assertNotNull(rule.judge(subject, context(history)))
        assertEquals(5, j.baseline.sampleSize)
        assertTrue(j.unusual)
    }

    @Test
    fun `the look-back counts the user's days, not UTC's`() {
        // 180 days before 10 March is 11 September; the window opens at 00:00 IST that day (18:30Z on the 10th).
        val start = RobustAmountRule.windowStart(now, 180, ist)
        assertEquals(Instant.parse("2025-09-10T18:30:00Z"), start)
        val justIn = (1..4).map { spend("400.00", "Swiggy", daysAgo = it.toLong()) } + spend("400.00", "Swiggy", at = start)
        val justOut = (1..4).map { spend("400.00", "Swiggy", daysAgo = it.toLong()) } + spend("400.00", "Swiggy", at = start.minusSeconds(1))
        assertEquals(5, assertNotNull(rule.judge(spend("4000.00"), context(justIn))).baseline.sampleSize)
        val out = assertNotNull(rule.judge(spend("4000.00"), context(justOut)))
        assertEquals(4, out.baseline.sampleSize, "the payment a second before the window is not counted")
        assertEquals("mean_multiple", out.tier.model.name)
    }

    @Test
    fun `merchant names match ignoring case and spaces around them`() {
        val history = usual(" SWIGGY ", 350, 420, 500, 610, 700)
        assertEquals(BaselineScope.MERCHANT, assertNotNull(rule.judge(spend("4800.00", "swiggy"), context(history))).tier.scope)
    }

    @Test
    fun `the tolerance passed in relaxes the verdict`() {
        val history = usual("Big Bazaar", 2000, 2050, 1950, 2020, 1980)
        assertNotNull(rule.evaluate(spend("3000.00", "Big Bazaar"), context(history)))
        assertNull(rule.evaluate(spend("3000.00", "Big Bazaar"), context(history, ToleranceLevel(2))))
    }

    @Test
    fun `the legacy chain is today's rule alone`() {
        val legacy = RobustAmountRule(RobustAmountRule.LEGACY_TIERS)
        assertEquals(30, legacy.lookbackDays)
        assertEquals(180, rule.lookbackDays)
        val history = usual("Swiggy", 350, 420, 500, 610, 700)
        // All five are within 30 days: over 3x their mean of ₹516 from ₹1,548.
        val result = assertNotNull(legacy.evaluate(spend("2100.00"), context(history)))
        assertTrue(result.reason.contains("for Food"), result.reason)
    }

    @Test
    fun `tiers and the rule refuse settings that make no sense`() {
        assertFailsWith<IllegalArgumentException> { BaselineTier(BaselineScope.MERCHANT, 0, MedianMadBaseline()) }
        assertFailsWith<IllegalArgumentException> { BaselineTier(BaselineScope.MERCHANT, 401, MedianMadBaseline()) }
        assertFailsWith<IllegalArgumentException> { RobustAmountRule(emptyList()) }
        assertFailsWith<IllegalArgumentException> { RobustAmountRule(minimumAmount = BigDecimal("-1")) }
        // A custom chain works too: one merchant tier with today's model.
        val custom = RobustAmountRule(listOf(BaselineTier(BaselineScope.MERCHANT, 90, MeanMultipleBaseline())), BigDecimal.ZERO)
        assertNotNull(custom.evaluate(spend("2000.00"), context(usual("Swiggy", 400, 500, 600))))
    }
}
