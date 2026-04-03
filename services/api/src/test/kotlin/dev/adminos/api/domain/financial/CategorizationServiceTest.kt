package dev.adminos.api.domain.financial

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for CategorizationService.
 * Tests known merchant lookup, pattern rules, and AI fallback.
 *
 * _Requirements: 6.1, 6.2, 6.3_
 */
class CategorizationServiceTest {

    private val service = CategorizationService()

    // ── Known merchant lookup ──

    @Test
    fun `known merchant Zomato returns food category with subcategory`() {
        val result = service.categorize("zomato")
        assertEquals(TransactionCategory.FOOD, result.category)
        assertEquals("food_delivery", result.subcategory)
        assertEquals(0.95, result.confidence)
        assertEquals("rules", result.source)
    }

    @Test
    fun `known merchant Netflix returns subscription category`() {
        val result = service.categorize("netflix")
        assertEquals(TransactionCategory.SUBSCRIPTION, result.category)
        assertEquals("streaming", result.subcategory)
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `known merchant Uber returns transport category`() {
        val result = service.categorize("uber")
        assertEquals(TransactionCategory.TRANSPORT, result.category)
        assertEquals("ride_hailing", result.subcategory)
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `known merchant Amazon returns shopping category`() {
        val result = service.categorize("amazon")
        assertEquals(TransactionCategory.SHOPPING, result.category)
        assertEquals("ecommerce", result.subcategory)
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `known merchant lookup is case-insensitive`() {
        val result = service.categorize("  ZOMATO  ")
        assertEquals(TransactionCategory.FOOD, result.category)
        assertEquals(0.95, result.confidence)
    }

    // ── Pattern-based rules ──

    @Test
    fun `pattern rule matches Swiggy Instamart as food`() {
        val result = service.categorize("Swiggy Instamart Order")
        assertEquals(TransactionCategory.FOOD, result.category)
        assertEquals(0.9, result.confidence)
    }

    @Test
    fun `pattern rule matches Ola ride as transport`() {
        val result = service.categorize("Ola Ride Payment")
        assertEquals(TransactionCategory.TRANSPORT, result.category)
        assertEquals(0.9, result.confidence)
    }

    @Test
    fun `pattern rule matches Flipkart as shopping`() {
        val result = service.categorize("Flipkart Marketplace")
        assertEquals(TransactionCategory.SHOPPING, result.category)
        assertEquals(0.85, result.confidence)
    }

    @Test
    fun `pattern rule matches EMI payment`() {
        val result = service.categorize("HDFC EMI Payment")
        assertEquals(TransactionCategory.EMI, result.category)
        assertEquals(0.85, result.confidence)
    }

    @Test
    fun `pattern rule matches electricity bill as utilities`() {
        val result = service.categorize("BESCOM Electricity Bill")
        assertEquals(TransactionCategory.UTILITIES, result.category)
        assertEquals(0.85, result.confidence)
    }

    @Test
    fun `pattern rule matches ATM withdrawal as transfer`() {
        val result = service.categorize("ATM Cash Withdrawal")
        assertEquals(TransactionCategory.TRANSFER, result.category)
        assertEquals(0.8, result.confidence)
    }

    // ── UPI transfer detection ──

    @Test
    fun `UPI payment method triggers transfer category`() {
        val result = service.categorize("Random Person", PaymentMethod.UPI)
        assertEquals(TransactionCategory.TRANSFER, result.category)
        assertEquals("upi", result.subcategory)
        assertEquals(0.75, result.confidence)
    }

    // ── AI fallback ──

    @Test
    fun `unknown merchant triggers AI fallback with low confidence`() {
        val txnId = UUID.randomUUID()
        service.clearAiFallbackQueue()

        val result = service.categorize("XYZ Unknown Store 12345", transactionId = txnId)

        assertEquals(TransactionCategory.OTHER, result.category)
        assertEquals(0.3, result.confidence)
        assertEquals("rules", result.source)
        assertTrue(service.getAiFallbackQueue().contains(txnId))
    }

    @Test
    fun `null merchant name returns other category`() {
        val result = service.categorize(null)
        assertEquals(TransactionCategory.OTHER, result.category)
        assertEquals(0.3, result.confidence)
    }
}
