package dev.adminos.api.domain.coach.lenses

import dev.adminos.api.domain.financial.MerchantCatalogue
import dev.adminos.api.domain.financial.PaymentMethod
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-901: the shipped lens file loads, and bad data fails loudly instead of matching nothing. */
class LensCatalogueTest {
    private fun file(vararg lenses: LensEntry, from: String = "22:00", to: String = "05:00") =
        LensFile("t", LateNightEntry(from, to), lenses.toList())

    private fun failure(f: LensFile): String = assertFailsWith<IllegalArgumentException> { LensCatalogue(f) }.message.orEmpty()

    @Test
    fun `the shipped file has the six default lenses in order`() {
        val c = LensCatalogue.DEFAULT
        assertEquals(listOf("quick_commerce", "food_delivery", "nights_out", "shopping", "pay_later", "travel"), c.lenses.map { it.id.value })
        assertEquals(LateNightWindow(LocalTime.of(22, 0), LocalTime.of(5, 0)), c.lateNight)
        assertEquals("Quick commerce", c.byId(LensId("quick_commerce"))?.label)
        assertNull(c.byId(LensId("gambling")))
        assertTrue(c.version.isNotBlank())
    }

    @Test
    fun `every brand a lens names exists in the merchant catalogue`() {
        val brands = LensCatalogue.DEFAULT.lenses.flatMap { l -> l.rules.filterIsInstance<LensRule.Brands>().flatMap { it.brands } }
        assertTrue(brands.size > 40, "${brands.size} brands")
        brands.forEach { assertTrue(MerchantCatalogue.DEFAULT.byBrand(it) != null, it) }
    }

    @Test
    fun `an unknown brand is rejected with its name`() {
        val msg = failure(file(LensEntry("x_lens", "X", brands = listOf("Zeptoo"))))
        assertTrue(msg.contains("Zeptoo"), msg)
    }

    @Test
    fun `unknown or transfer categories are rejected`() {
        assertTrue(failure(file(LensEntry("x_lens", "X", categories = listOf("groceries")))).contains("groceries"))
        assertTrue(failure(file(LensEntry("x_lens", "X", categories = listOf("transfer")))).contains("transfer"))
    }

    @Test
    fun `the pay later lens matches by payment method only, never by provider brand`() {
        val lens = LensCatalogue.DEFAULT.byId(LensId("pay_later"))!!
        assertEquals(listOf(LensRule.PaymentMethods(setOf(PaymentMethod.PAY_LATER))), lens.rules)
    }

    @Test
    fun `a payment-method rule is accepted in any case and an unknown method is rejected with its name`() {
        val c = LensCatalogue(file(LensEntry("x_lens", "X", paymentMethods = listOf("Pay_Later", "wallet"))))
        assertEquals(LensRule.PaymentMethods(setOf(PaymentMethod.PAY_LATER, PaymentMethod.WALLET)), c.lenses.single().rules.single())
        val msg = failure(file(LensEntry("x_lens", "X", paymentMethods = listOf("bnpl"))))
        assertTrue(msg.contains("bnpl"), msg)
        assertFailsWith<IllegalArgumentException> { LensRule.PaymentMethods(emptySet()) }
    }

    @Test
    fun `a category rule is accepted in any case`() {
        val c = LensCatalogue(file(LensEntry("fun_money", "Fun", categories = listOf("Entertainment"))))
        assertTrue(c.lenses.single().rules.single() is LensRule.Categories)
    }

    @Test
    fun `short keywords, bad subcategories, bad ids and duplicates are rejected`() {
        assertTrue(failure(file(LensEntry("x_lens", "X", keywords = listOf("ba")))).contains("too short"))
        assertTrue(failure(file(LensEntry("x_lens", "X", subcategories = listOf("Food Delivery")))).contains("subcategory"))
        assertTrue(failure(file(LensEntry("Bad-Id", "X", keywords = listOf("bar")))).contains("Bad-Id"))
        assertTrue(failure(file(LensEntry("x_lens", "X", keywords = listOf("bar")), LensEntry("x_lens", "Y", keywords = listOf("pub")))).contains("duplicate"))
    }

    @Test
    fun `a lens without rules, a blank label, no lenses or a blank version are rejected`() {
        assertTrue(failure(file(LensEntry("x_lens", "X"))).contains("no brands"))
        assertTrue(failure(file(LensEntry("x_lens", " ", keywords = listOf("bar")))).contains("label"))
        assertTrue(failure(file()).contains("no lenses"))
        assertTrue(failure(LensFile(" ", LateNightEntry("22:00", "05:00"), listOf(LensEntry("x_lens", "X", keywords = listOf("bar"))))).contains("version"))
    }

    @Test
    fun `a bad late-night window is rejected`() {
        assertTrue(failure(file(LensEntry("x_lens", "X", keywords = listOf("bar")), from = "10pm")).contains("22:00"))
        assertTrue(failure(file(LensEntry("x_lens", "X", keywords = listOf("bar")), from = "05:00", to = "05:00")).contains("same time"))
    }

    @Test
    fun `a missing resource fails clearly`() {
        val ex = assertFailsWith<IllegalStateException> { LensCatalogue.load("/coach/none.json") }
        assertTrue(ex.message.orEmpty().contains("not found"))
    }

    @Test
    fun `lens ids parse only in snake case`() {
        assertEquals(LensId("food_delivery"), LensId.parse("food_delivery"))
        assertNull(LensId.parse("Food"))
        assertNull(LensId.parse("x"))
        assertFailsWith<IllegalArgumentException> { LensId("no spaces") }
        assertEquals("travel", LensId("travel").toString())
    }

    @Test
    fun `the late-night window crosses midnight or not`() {
        val night = LateNightWindow(LocalTime.of(22, 0), LocalTime.of(5, 0))
        assertTrue(LocalTime.of(22, 0) in night)
        assertTrue(LocalTime.of(23, 59) in night)
        assertTrue(LocalTime.of(0, 0) in night)
        assertTrue(LocalTime.of(4, 59) in night)
        assertFalse(LocalTime.of(5, 0) in night)
        assertFalse(LocalTime.of(21, 59) in night)
        val small = LateNightWindow(LocalTime.of(0, 0), LocalTime.of(5, 0))
        assertTrue(LocalTime.of(1, 0) in small)
        assertFalse(LocalTime.of(23, 0) in small)
        assertFalse(LocalTime.of(5, 0) in small)
        assertEquals("22:00–05:00", night.toString())
    }

    @Test
    fun `a keyword rule needs a keyword`() {
        assertFailsWith<IllegalArgumentException> { LensRule.Keywords(emptySet()) }
    }
}
