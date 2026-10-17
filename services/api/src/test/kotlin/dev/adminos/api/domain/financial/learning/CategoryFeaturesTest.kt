package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionNature
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CategoryFeaturesTest {

    private fun names(input: CategoryInput) = CategoryFeatures.of(input).map { it.name }

    @Test
    fun `payee words and their character n-grams are features with the word as the cue`() {
        val features = CategoryFeatures.of(CategoryInput(merchantName = "Sai Medicos"))
        assertTrue(Feature("w:medicos", "medicos") in features)
        assertTrue(Feature("c:^med", "medicos") in features)
        assertTrue(Feature("c:icos$", "medicos") in features)
        assertTrue(Feature("c:medic", "medicos") in features, "shares 'medic' with 'medical'")
    }

    @Test
    fun `a brand glued into a UPI handle is found by the handle's n-grams`() {
        val names = names(CategoryInput(merchantName = null, upiVpa = "netflixupi@hdfcbank"))
        assertTrue("h:netflixupi" in names)
        assertTrue("hc:netfl" in names)
        assertTrue("psp:hdfcbank" in names)
    }

    @Test
    fun `a phone-number handle is one marker and its digits are never features`() {
        val names = names(CategoryInput(merchantName = "Ravi Kumar", upiVpa = "9800000012@ybl"))
        assertTrue("h:#phone" in names)
        assertTrue(names.none { it.contains("9800000012") })
    }

    @Test
    fun `narration words add notes but drop bank structure, references, the UPI ID and the payee again`() {
        val input = CategoryInput(
            merchantName = "Ramu Chai",
            merchantRaw = "UPI-RAMU CHAI-ramuchai@ybl-YESB0000020-428911100020-LUNCH",
            upiVpa = "ramuchai@ybl"
        )
        val narration = names(input).filter { it.startsWith("n:") }
        assertEquals(listOf("n:lunch"), narration)
    }

    @Test
    fun `generic words shared by unrelated payees are not features`() {
        val names = names(CategoryInput(merchantName = "Nippon India Mf", merchantRaw = "NACH-DR-NIPPON INDIA MF-SIP"))
        assertFalse(names.any { it == "w:india" || it.startsWith("c:") && it.contains("ndia") })
        assertTrue("w:nippon" in names)
    }

    @Test
    fun `method, nature and amount bucket are features without cues`() {
        val features = CategoryFeatures.of(CategoryInput(merchantName = "X", paymentMethod = PaymentMethod.CARD_CREDIT,
            nature = TransactionNature.REFUND, amount = BigDecimal("1499.00")))
        assertTrue(Feature("pm:card_credit") in features)
        assertTrue(Feature("nat:refund") in features)
        assertTrue(Feature("amt:2") in features)
    }

    @Test
    fun `amount buckets run from under 100 rupees to 50,000 and above`() {
        assertEquals(0, CategoryFeatures.bucketOf(BigDecimal("99.99")))
        assertEquals(1, CategoryFeatures.bucketOf(BigDecimal("100.00")))
        assertEquals(4, CategoryFeatures.bucketOf(BigDecimal("49999.99")))
        assertEquals(5, CategoryFeatures.bucketOf(BigDecimal("50000.00")))
    }

    @Test
    fun `an input with nothing but its nature has a single feature`() {
        assertEquals(listOf("nat:spend"), names(CategoryInput(merchantName = null)))
        assertTrue(CategoryInput(merchantName = " ").isAnonymous)
    }

    @Test
    fun `features are distinct and very long words are dropped`() {
        val long = "x".repeat(CategoryFeatures.MAX_WORD + 1)
        val names = names(CategoryInput(merchantName = "Chai Chai $long", merchantRaw = long))
        assertEquals(names.distinct(), names)
        assertTrue(names.none { it.contains(long) })
    }

    @Test
    fun `a negative amount is rejected and a feature needs a name`() {
        assertFailsWith<IllegalArgumentException> { CategoryInput(merchantName = "X", amount = BigDecimal("-1")) }
        assertFailsWith<IllegalArgumentException> { Feature(" ") }
    }
}
