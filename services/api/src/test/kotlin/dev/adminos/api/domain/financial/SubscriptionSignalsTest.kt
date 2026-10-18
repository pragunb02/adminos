package dev.adminos.api.domain.financial

import dev.adminos.api.domain.financial.SubscriptionSignal.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-516: what makes regular charges a subscription, and what a plan switch looks like. */
class SubscriptionSignalsTest {
    private val user = UUID.randomUUID()
    private val start = Instant.parse("2026-06-01T06:30:00Z")
    private val everyday = CatalogueEverydaySpending()

    private fun charges(merchant: String?, vararg amounts: String, category: TransactionCategory = TransactionCategory.OTHER,
                        subcategory: String? = null, raw: String? = null, mandate: Boolean = false) =
        amounts.mapIndexed { i, a ->
            Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal(a), merchantName = merchant, merchantRaw = raw,
                category = category, subcategory = subcategory, metadata = if (mandate) mapOf("mandate" to "true") else null,
                transactedAt = start.plusSeconds(i * 30L * 86_400))
        }

    private fun assess(c: List<Transaction>) = SubscriptionSignals.assess(c, everyday)

    @Test
    fun `a known service with the same price is a subscription`() {
        val e = assess(charges("Netflix", "649", "649", "649"))
        assertTrue(e.isSubscription)
        assertEquals(setOf(KNOWN_SERVICE, STABLE_AMOUNT), e.signals)
        assertEquals("a subscription service", e.reason)
    }

    @Test
    fun `groceries, food and quick commerce are never subscriptions, even at the same price`() {
        for (m in listOf("Zepto", "BigBasket", "Swiggy", "Starbucks", "Domino's")) {
            val e = assess(charges(m, "499", "499", "499"))
            assertFalse(e.isSubscription, m)
            assertTrue(EVERYDAY in e.signals, m)
            assertEquals("groceries, food or quick commerce", e.reason)
        }
        assertFalse(assess(charges("Tiffin Corner", "300", "300", "300", category = TransactionCategory.FOOD)).isSubscription, "food by category")
    }

    @Test
    fun `a membership on AutoPay at an everyday brand is a subscription`() {
        val e = assess(charges("Swiggy", "99", "99", "99", mandate = true))
        assertTrue(e.isSubscription)
        assertTrue(EVERYDAY in e.signals && MANDATE in e.signals)
    }

    @Test
    fun `loans, investments, insurance and wallets are never subscriptions, even by mandate`() {
        for (m in listOf("Zerodha", "Groww", "LIC", "Paytm", "LazyPay")) {
            val e = assess(charges(m, "5000", "5000", "5000", mandate = true))
            assertFalse(e.isSubscription, m)
            assertEquals("a loan, investment, insurance or wallet payment", e.reason)
        }
        assertFalse(assess(charges("Home Loan", "25000", "25000", "25000", category = TransactionCategory.EMI)).isSubscription)
    }

    @Test
    fun `amounts that vary are not a subscription`() {
        val e = assess(charges("Sharma General Store", "820", "1140", "760", "990"))
        assertFalse(e.isSubscription)
        assertFalse(STABLE_AMOUNT in e.signals)
        assertEquals("the amounts vary", e.reason)
    }

    @Test
    fun `a prepaid plan and a mandate are subscriptions`() {
        assertEquals("a prepaid plan", assess(charges("Jio", "299", "299", "299")).reason)
        assertEquals("a prepaid plan", assess(charges("Tata Play", "350", "350")).reason)
        assertEquals("paid by a mandate", assess(charges("Times Prime", "99", "99", mandate = true)).reason)
    }

    @Test
    fun `a merchant the catalogue knows as something else is not a subscription`() {
        val e = assess(charges("Indian Oil", "2000", "2000", "2000"))
        assertFalse(e.isSubscription)
        assertTrue(OTHER_KIND in e.signals)
        assertEquals("fuel isn't a subscription service", e.reason)
        assertEquals("electricity isn't a subscription service", assess(charges("BESCOM", "1400", "1400")).reason)
        assertEquals("movies events isn't a subscription service", assess(charges("BookMyShow", "500", "500", "500")).reason)
    }

    @Test
    fun `an unknown merchant at the same price is a subscription unless it is shopping or transport`() {
        assertEquals("the same amount at regular intervals", assess(charges("Iron Paradise Gym", "1500", "1500", "1500")).reason)
        assertEquals("shopping spending", assess(charges("Corner Boutique", "999", "999", "999", category = TransactionCategory.SHOPPING)).reason)
        assertEquals("transport spending", assess(charges("City Parking", "500", "500", "500", category = TransactionCategory.TRANSPORT)).reason)
        assertTrue(assess(charges(null, "100", "100", subcategory = "news")).isSubscription, "a nameless charge goes by its subcategory")
    }

    @Test
    fun `a mandate is seen from the matcher, the parser or the narration`() {
        val t = charges("X", "1").single()
        assertFalse(SubscriptionSignals.paidByMandate(t))
        assertTrue(SubscriptionSignals.paidByMandate(t.copy(isRecurring = true)))
        assertTrue(SubscriptionSignals.paidByMandate(t.copy(recurringGroupId = UUID.randomUUID())))
        assertTrue(SubscriptionSignals.paidByMandate(t.copy(metadata = mapOf("umn" to "abc@upi"))))
        for (raw in listOf("ACH D- NACH LIC", "UPI AUTOPAY NETFLIX", "UPI AUTO PAY", "E-MANDATE DEBIT", "SI STANDING INSTRUCTION")) {
            assertTrue(SubscriptionSignals.paidByMandate(t.copy(merchantRaw = raw)), raw)
        }
        assertFalse(SubscriptionSignals.paidByMandate(t.copy(merchantRaw = "MANDATEX STORES")), "whole words only")
    }

    @Test
    fun `stable allows one price change, more only when rising for a vouched service`() {
        fun s(vararg a: String, vouched: Boolean = false) = SubscriptionSignals.isStable(a.map(::BigDecimal), vouched)
        assertTrue(s())
        assertTrue(s("649"))
        assertTrue(s("119", "119", "139"), "one rise")
        assertTrue(s("149", "155"), "within 10 percent")
        assertTrue(s("50", "59"), "within the 10 rupee floor")
        assertFalse(s("299", "399"), "two charges a step apart need a voucher")
        assertTrue(s("299", "399", vouched = true))
        assertFalse(s("199", "249", "299"), "two rises")
        assertTrue(s("199", "249", "299", vouched = true), "a service may rise twice")
        assertFalse(s("299", "199", "299", vouched = true), "down and up again is not a price")
        assertTrue(s("0.00", "1.00", "129", "129"), "token charges are ignored")
        assertFalse(SubscriptionSignals.differ(BigDecimal("100"), BigDecimal("110")))
        assertTrue(SubscriptionSignals.differ(BigDecimal("100"), BigDecimal("110.01")))
    }

    @Test
    fun `evidence needs charges and a reason`() {
        assertFailsWith<IllegalArgumentException> { SubscriptionSignals.assess(emptyList(), everyday) }
        assertFailsWith<IllegalArgumentException> { SubscriptionEvidence(emptySet(), false, " ") }
    }

    @Test
    fun `everyday spending combines its parts`() {
        val t = charges("Corner Store", "1").single()
        assertFalse(everyday.isEveryday(t))
        assertTrue(everyday.isEveryday(t.copy(subcategory = "groceries")))
        assertTrue(EverydaySpending.anyOf(everyday, { it.merchantName == "Corner Store" }).isEveryday(t))
        assertFalse(EverydaySpending.anyOf().isEveryday(t))
    }

    @Test
    fun `a charge 8 to 14 times the usual one is a plan switch, either way`() {
        fun find(vararg a: String) = PlanSwitch.find(a.map(::BigDecimal))
        assertEquals(PlanSwitch(3, PlanSwitchDirection.UP), find("1999", "1999", "1999", "19990"))
        assertEquals(PlanSwitch(2, PlanSwitchDirection.DOWN), find("1990", "1990", "199", "199"))
        assertEquals(PlanSwitch(1, PlanSwitchDirection.UP), find("130", "1040"), "8x")
        assertEquals(PlanSwitch(1, PlanSwitchDirection.UP), find("100", "1400"), "14x")
        assertNull(find("100", "1500"), "15x is something else")
        assertNull(find("299", "1499"), "5x is a price change, not a plan")
        assertNull(find("649", "649", "799"))
        assertNull(find("1999"))
        assertNull(find())
        assertNull(find("0.00", "129"), "a free month isn't a cheap plan")
        assertNull(find("129", "0.00"))
        assertEquals(PlanSwitch(3, PlanSwitchDirection.UP), find("0.00", "130", "130", "1300"), "the median ignores token charges")
        assertEquals(PlanSwitch(4, PlanSwitchDirection.DOWN), find("119", "1189", "1189", "1189", "119"), "the latest switch wins")
        assertEquals(PlanSwitch(2, PlanSwitchDirection.DOWN), find("1999", "1999", "199", "1999"), "one promo charge is not a switch back")
        assertEquals(PlanSwitch(1, PlanSwitchDirection.DOWN), find("1999", "199", "1999", "1999"), "only where the price moved from the charge before")
        assertEquals(BillingCycle.YEARLY, PlanSwitch(1, PlanSwitchDirection.UP).impliedCycle)
        assertEquals(BillingCycle.MONTHLY, PlanSwitch(1, PlanSwitchDirection.UP).impliedPrevious)
        assertEquals(BillingCycle.MONTHLY, PlanSwitch(1, PlanSwitchDirection.DOWN).impliedCycle)
        assertEquals(BillingCycle.YEARLY, PlanSwitch(1, PlanSwitchDirection.DOWN).impliedPrevious)
    }
}
