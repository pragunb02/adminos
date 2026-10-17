package dev.adminos.api.domain.financial

import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-715: when a subscription's price changed, and the rise record that makes the alert once. */
class PriceChangeRuleTest {
    private val start = LocalDate.of(2026, 1, 11)

    /** Monthly charges of [amounts], oldest first, in rupees unless [currency]. */
    private fun monthly(vararg amounts: String, currency: String = "INR") =
        amounts.mapIndexed { i, a -> Charge(UUID.randomUUID(), BigDecimal(a), currency, start.plusMonths(i.toLong())) }

    private fun rule(vararg amounts: String, cycle: BillingCycle = BillingCycle.MONTHLY) = PriceChangeRule.evaluate(monthly(*amounts), cycle)

    @Test
    fun `a charge well above the median of the three before it is a rise`() {
        val c = assertNotNull(rule("649.00", "649.00", "649.00", "799.00"))
        assertTrue(c.isRise)
        assertEquals(BigDecimal("649.00"), c.usual)
        assertEquals(BigDecimal("799.00"), c.now)
        assertEquals(BigDecimal("23.11"), c.pct)
        assertEquals(start.plusMonths(3), c.on)
    }

    @Test
    fun `one odd charge before it doesn't make a rise`() {
        // A discounted month in between: the median of 649, 499, 649 is 649.
        assertNull(rule("649.00", "499.00", "649.00", "649.00"))
        // Only the last three count: an old lower price is forgotten.
        assertNull(rule("499.00", "649.00", "649.00", "649.00", "649.00"))
    }

    @Test
    fun `a drop is a change too, but not a rise`() {
        val c = assertNotNull(rule("649.00", "649.00", "649.00", "499.00"))
        assertFalse(c.isRise)
        assertEquals(BigDecimal("-23.11"), c.pct)
    }

    @Test
    fun `a change is at least 5 percent and at least 20 rupees`() {
        assertNull(rule("649.00", "649.00", "669.00"), "₹20 is under 5% of ₹649 (₹32.45)")
        assertNotNull(rule("649.00", "649.00", "682.00"))
        assertNull(rule("199.00", "199.00", "218.00"), "5% of ₹199 is under ₹20")
        assertNotNull(rule("199.00", "199.00", "219.00"))
    }

    @Test
    fun `amounts are compared in whole rupees, so GST rounding is not a change`() {
        assertNull(rule("648.99", "649.00", "649.49"))
        assertNull(rule("649.00", "649.00", "681.49"), "₹681 is ₹32 more: under ₹32.45")
        assertNotNull(rule("649.00", "649.00", "681.50"), "₹682 is ₹33 more")
    }

    @Test
    fun `a monthly plan turned yearly, or back, is a plan switch, not a price change`() {
        assertNull(rule("199.00", "199.00", "199.00", "1999.00"), "10x: a yearly plan")
        assertNull(rule("1499.00", "1499.00", "149.00"), "a tenth: back to monthly")
        assertNotNull(rule("199.00", "199.00", "1499.00"), "7.5x is a rise, not a yearly plan")
        assertNotNull(rule("1.00", "1.00", "199.00"), "an intro price ending is a rise")
    }

    @Test
    fun `monthly plans need two earlier charges, yearly and quarterly ones one`() {
        assertNull(rule("649.00", "799.00"))
        val yearly = assertNotNull(rule("1499.00", "1799.00", cycle = BillingCycle.YEARLY))
        assertTrue(yearly.isRise)
        assertNotNull(rule("399.00", "499.00", cycle = BillingCycle.QUARTERLY))
        assertNull(rule("49.00", "99.00", cycle = BillingCycle.WEEKLY))
        assertNull(PriceChangeRule.evaluate(emptyList(), BillingCycle.MONTHLY))
    }

    @Test
    fun `only charges in the latest charge's currency are compared`() {
        val mixed = monthly("10.00", "10.00", currency = "USD") + monthly("899.00", currency = "INR").map { it.copy(on = start.plusMonths(2)) }
        assertNull(PriceChangeRule.evaluate(mixed, BillingCycle.MONTHLY))
        val usd = assertNotNull(PriceChangeRule.evaluate(monthly("20.00", "20.00", "45.00", currency = "USD"), BillingCycle.MONTHLY))
        assertEquals("USD", usd.currency)
    }

    @Test
    fun `charges are put in date order first, and the median of two is their mean`() {
        val c = assertNotNull(PriceChangeRule.evaluate(monthly("600.00", "700.00", "799.00").reversed(), BillingCycle.MONTHLY))
        assertEquals(BigDecimal("650.00"), c.usual)
    }

    @Test
    fun `absurd amounts and thousands of charges are handled without overflow or slowdown`() {
        val big = assertNotNull(rule("999999999999.00", "999999999999.00", "1999999999999.00", cycle = BillingCycle.MONTHLY))
        assertEquals(BigDecimal("100.00"), big.pct)
        assertEquals(1_999_999_999_999L, PriceRise.of(UUID.randomUUID(), UUID.randomUUID(), big).priceKey)
        val many = (0 until 5000).map { Charge(UUID.randomUUID(), BigDecimal("649.00"), "INR", start.plusDays(it.toLong())) } +
            Charge(UUID.randomUUID(), BigDecimal("799.00"), "INR", start.plusDays(5000))
        assertEquals(BigDecimal("649.00"), assertNotNull(PriceChangeRule.evaluate(many, BillingCycle.DAILY)).usual)
    }

    @Test
    fun `prices under one rupee are never compared`() {
        assertNull(rule("0.40", "0.40", "0.45"))
    }

    @Test
    fun `charges, changes and rises validate themselves`() {
        assertFailsWith<IllegalArgumentException> { Charge(UUID.randomUUID(), BigDecimal.ZERO, "INR", start) }
        assertFailsWith<IllegalArgumentException> { Charge(UUID.randomUUID(), BigDecimal.ONE, " ", start) }
        assertFailsWith<IllegalArgumentException> { PriceChange(BigDecimal.TEN, BigDecimal.TEN, "INR", start, UUID.randomUUID()) }
        assertFailsWith<IllegalArgumentException> { PriceChange(BigDecimal.ZERO, BigDecimal.TEN, "INR", start, UUID.randomUUID()) }
        val drop = PriceChange(BigDecimal.TEN, BigDecimal.ONE, "INR", start, UUID.randomUUID())
        assertFailsWith<IllegalArgumentException> { PriceRise.of(UUID.randomUUID(), UUID.randomUUID(), drop) }
        assertFailsWith<IllegalArgumentException> {
            PriceRise(userId = UUID.randomUUID(), subscriptionId = UUID.randomUUID(), oldAmount = BigDecimal.ZERO, newAmount = BigDecimal.ONE,
                currency = "INR", chargedOn = start, transactionId = UUID.randomUUID())
        }
    }

    @Test
    fun `a rise is keyed by its new price in whole rupees`() {
        val change = PriceChange(BigDecimal("649.00"), BigDecimal("799.49"), "INR", start, UUID.randomUUID())
        val rise = PriceRise.of(UUID.randomUUID(), UUID.randomUUID(), change)
        assertEquals(799L, rise.priceKey)
        assertEquals(800L, rise.copy(newAmount = BigDecimal("799.50")).priceKey)
        assertEquals(change.transactionId, rise.transactionId)
    }

    @Test
    fun `the in-memory store records each subscription's price once and lists what is untold`() = runBlocking<Unit> {
        val repo = InMemoryPriceRiseRepository()
        val user = UUID.randomUUID()
        val sub = UUID.randomUUID()
        fun rise(amount: String) = PriceRise(userId = user, subscriptionId = sub, oldAmount = BigDecimal("649.00"), newAmount = BigDecimal(amount),
            currency = "INR", chargedOn = start, transactionId = UUID.randomUUID())
        val first = rise("799.00")
        assertTrue(repo.recordIfNew(first))
        assertFalse(repo.recordIfNew(rise("799.20")), "the same price in rupees")
        assertTrue(repo.recordIfNew(rise("899.00")))
        assertEquals(2, repo.findUnnotified(user).size)
        repo.markNotified(first.id, java.time.Instant.EPOCH)
        repo.markNotified(UUID.randomUUID(), java.time.Instant.EPOCH) // unknown: nothing happens
        assertEquals(listOf(BigDecimal("899.00")), repo.findUnnotified(user).map { it.newAmount })
        assertEquals(emptyList(), repo.findUnnotified(UUID.randomUUID()))
    }
}
