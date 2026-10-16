package dev.adminos.api.domain.financial

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for CategorizationService.
 * Built-in steps: known merchant lookup and whole-word patterns. Remembered rules: CategoryCorrectionTest.
 *
 * _Requirements: 6.1, 6.2, 6.3_
 */
class CategorizationServiceTest {

    private val service = CategorizationService()

    // ── Known merchant lookup ──

    @Test
    fun `known merchant Zomato returns food category with subcategory`() {
        val result = service.builtIn("zomato")
        assertEquals(TransactionCategory.FOOD, result.category)
        assertEquals("food_delivery", result.subcategory)
        assertEquals(0.95, result.confidence)
        assertEquals("rules", result.source)
    }

    @Test
    fun `known merchant Netflix returns subscription category`() {
        val result = service.builtIn("netflix")
        assertEquals(TransactionCategory.SUBSCRIPTION, result.category)
        assertEquals("streaming", result.subcategory)
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `known merchant Uber returns transport category`() {
        val result = service.builtIn("uber")
        assertEquals(TransactionCategory.TRANSPORT, result.category)
        assertEquals("ride_hailing", result.subcategory)
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `known merchant Amazon returns shopping category`() {
        val result = service.builtIn("amazon")
        assertEquals(TransactionCategory.SHOPPING, result.category)
        assertEquals("ecommerce", result.subcategory)
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `known merchant lookup is case-insensitive`() {
        val result = service.builtIn("  ZOMATO  ")
        assertEquals(TransactionCategory.FOOD, result.category)
        assertEquals(0.95, result.confidence)
    }

    // ── Pattern-based rules ──

    @Test
    fun `pattern rule matches Swiggy Instamart as food`() {
        val result = service.builtIn("Swiggy Instamart Order")
        assertEquals(TransactionCategory.FOOD, result.category)
        assertEquals(0.9, result.confidence)
    }

    @Test
    fun `pattern rule matches Ola ride as transport`() {
        val result = service.builtIn("Ola Ride Payment")
        assertEquals(TransactionCategory.TRANSPORT, result.category)
        assertEquals(0.9, result.confidence)
    }

    @Test
    fun `pattern rule matches Flipkart as shopping`() {
        val result = service.builtIn("Flipkart Marketplace")
        assertEquals(TransactionCategory.SHOPPING, result.category)
        assertEquals(0.85, result.confidence)
    }

    @Test
    fun `pattern rule matches EMI payment`() {
        val result = service.builtIn("HDFC EMI Payment")
        assertEquals(TransactionCategory.EMI, result.category)
        assertEquals(0.85, result.confidence)
    }

    @Test
    fun `pattern rule matches electricity bill as utilities`() {
        val result = service.builtIn("BESCOM Electricity Bill")
        assertEquals(TransactionCategory.UTILITIES, result.category)
        assertEquals(0.85, result.confidence)
    }

    @Test
    fun `pattern rule matches ATM withdrawal as transfer`() {
        val result = service.builtIn("ATM Cash Withdrawal")
        assertEquals(TransactionCategory.TRANSFER, result.category)
        assertEquals(0.8, result.confidence)
    }

    // ── No guessed transfers (AOS-210): transfers come from TransferStep ──

    @Test
    fun `unknown payee is Other, not Transfer`() {
        val result = service.builtIn("Random Person")
        assertEquals(TransactionCategory.OTHER, result.category)
        assertEquals(0.3, result.confidence)
        assertEquals("rules", result.source)
    }

    @Test
    fun `NEFT or IMPS in the narration says nothing about the category`() {
        assertEquals(TransactionCategory.OTHER, service.builtIn("NEFT-ACME CONSULTING").category)
        assertEquals(TransactionCategory.OTHER, service.builtIn("IMPS RAVI KUMAR").category)
    }

    @Test
    fun `patterns match whole words only`() {
        assertEquals(TransactionCategory.OTHER, service.builtIn("Motorola Store").category, "ola")
        assertEquals(TransactionCategory.OTHER, service.builtIn("Premium Tailors").category, "emi")
        assertEquals(TransactionCategory.OTHER, service.builtIn("Vegas Lounge").category, "gas")
    }

    @Test
    fun `null merchant name returns other category`() {
        val result = service.builtIn(null)
        assertEquals(TransactionCategory.OTHER, result.category)
        assertEquals(0.3, result.confidence)
    }
}
