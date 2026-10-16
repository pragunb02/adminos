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
