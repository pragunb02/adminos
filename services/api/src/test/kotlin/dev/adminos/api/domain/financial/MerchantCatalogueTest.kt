package dev.adminos.api.domain.financial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** AOS-206: the shipped catalogue loads, and bad data fails loudly instead of misfiring. */
class MerchantCatalogueTest {

    @Test
    fun `the shipped catalogue is valid and covers the common Indian merchants`() {
        val c = MerchantCatalogue.DEFAULT
        assertTrue(c.merchants.size >= 100, "${c.merchants.size} brands")
        assertEquals(TransactionCategory.FOOD, c.byBrand("Swiggy")!!.category)
        assertEquals(TransactionCategory.SHOPPING, c.byBrand("blinkit")!!.category)
        assertEquals(TransactionCategory.UTILITIES, c.byBrand("BESCOM")!!.category)
    }

    @Test
    fun `the categorizer and normalizer share it`() {
        val brand = dev.adminos.api.domain.ingestion.pipeline.MerchantNormalizer().normalize("ZEPTO MARKETPLACE")
        assertEquals("Zepto", brand)
        assertEquals(TransactionCategory.SHOPPING, CategorizationService().builtIn(brand).category)
    }

    @Test
    fun `the brands the habit lenses need are recognised with their category`() {
        val normalizer = dev.adminos.api.domain.ingestion.pipeline.MerchantNormalizer()
        val cases = listOf(
            Triple("UPI-BBNOW-bbnow@icici-ICIC0000217-428911300017-GROCERY", "BB Now", "groceries"),
            Triple("FLIPKART MINUTES ORDER", "Flipkart Minutes", "groceries"),
            Triple("AMAZON NOW", "Amazon Now", "groceries"),
            Triple("LAZYPAY PRIVATE LIMITED", "LazyPay", "pay_later"),
            Triple("ONE SIGMA TECHNOLOGIES SIMPL", "Simpl", "pay_later"),
            Triple("AMAZON PAY LATER REPAYMENT", "Amazon Pay Later", "pay_later"),
            Triple("PAYTM POSTPAID BILL", "Paytm Postpaid", "pay_later"),
            Triple("SPICEJET LTD", "SpiceJet", "flights"),
            Triple("AIR INDIA EXPRESS", "Air India Express", "flights"),
            Triple("ABHIBUS SERVICES", "AbhiBus", "bus"),
            Triple("EASY TRIP PLANNERS", "EaseMyTrip", "travel"),
            Triple("TOIT BREWPUB", "Toit", "nightlife"),
            Triple("PAYTM INSIDER EVENTS", "Paytm Insider", "movies_events"),
            Triple("LIFESTYLE INTERNATIONAL", "Lifestyle", "fashion"),
            Triple("BRAINBEES SOLUTIONS", "FirstCry", "ecommerce")
        )
        for ((raw, brand, sub) in cases) {
            assertEquals(brand, normalizer.normalize(raw), raw)
            assertEquals(sub, CategorizationService().builtIn(brand).subcategory, brand)
        }
        assertEquals("Amazon", normalizer.normalize("AMAZON PAY INDIA"), "the longer Pay Later alias doesn't take plain Amazon Pay")
        assertEquals("BigBasket", normalizer.normalize("INNOVATIVE RETAIL CONCEPTS"))
    }

    @Test
    fun `an alias claimed by two brands is rejected`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            MerchantCatalogue("t", listOf(CatalogueMerchant("A", "food", aliases = listOf("ACME")), CatalogueMerchant("B", "food", aliases = listOf("acme"))))
        }
        assertTrue(ex.message!!.contains("both"))
    }

    @Test
    fun `unknown categories and too-short aliases are rejected`() {
        assertFailsWith<IllegalArgumentException> { MerchantCatalogue("t", listOf(CatalogueMerchant("A", "groceries", aliases = listOf("ACME")))) }
        assertFailsWith<IllegalArgumentException> { MerchantCatalogue("t", listOf(CatalogueMerchant("Vi", "utilities", aliases = listOf("VI")))) }
    }
}
