package dev.adminos.api.domain.financial.ai

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** AOS-512, D-33: only clean business names may be shown to a model; a person's name never is. */
class AiPrivacyGateTest {

    private fun txn(name: String?, raw: String? = name, method: PaymentMethod? = PaymentMethod.UPI, vpa: String? = null) = Transaction(
        userId = UUID(0, 1), type = TransactionType.DEBIT, amount = BigDecimal("499.00"), merchantName = name, merchantRaw = raw,
        paymentMethod = method, upiVpa = vpa, transactedAt = Instant.parse("2026-10-01T10:00:00Z")
    )

    @Test
    fun `a card payment's merchant is asked with only its name, method and the category list`() {
        val q = AiPrivacyGate.question(txn("Steam", raw = "STEAM PURCHASE 512345XXXXXX4421", method = PaymentMethod.CARD_CREDIT))
        assertEquals(AiCategoryQuestion("Steam", PaymentMethod.CARD_CREDIT, AI_ALLOWED_CATEGORIES), q)
    }

    @Test
    fun `a UPI payment to a person is never asked about`() {
        assertNull(AiPrivacyGate.question(txn("Rohit Sharma", "UPI-ROHIT SHARMA-rohit.sharma92@oksbi-SBIN0001001-428912100001-DINNER", vpa = "rohit.sharma92@oksbi")))
        assertNull(AiPrivacyGate.question(txn("Vikram Singh", "IMPS-428912100006-VIKRAM SINGH-HDFC-XXXXXX1234-LOAN RETURN", PaymentMethod.NETBANKING)))
        assertNull(AiPrivacyGate.question(txn("Deepak Joshi", raw = null, vpa = "deepak.joshi@okhdfcbank")))
    }

    @Test
    fun `business words in a payment note do not let a person's name through`() {
        assertNull(AiPrivacyGate.question(txn("Ravi Kumar", "UPI-RAVI KUMAR-ravi.k@oksbi-SBIN0001001-428912100001-MOBILE RECHARGE STORE")))
    }

    @Test
    fun `a business name, a gateway descriptor or a registered company may be asked about`() {
        assertEquals("Sri Lakshmi General Stores", AiPrivacyGate.question(txn("Sri Lakshmi General Stores"))?.merchant)
        assertEquals("Unacademy", AiPrivacyGate.question(txn("Unacademy", "RAZORPAY*UNACADEMY", PaymentMethod.NETBANKING))?.merchant)
        assertEquals("Bajaj Finance", AiPrivacyGate.question(txn("Bajaj Finance", "ACH D- BAJAJ FINANCE LTD-P400XXXX1234", PaymentMethod.NETBANKING))?.merchant)
    }

    @Test
    fun `names that could hold an identifier or are too short are never sent`() {
        val card = PaymentMethod.CARD_DEBIT
        assertNull(AiPrivacyGate.question(txn(null, method = card)))
        assertNull(AiPrivacyGate.question(txn("Ab", method = card)))
        assertNull(AiPrivacyGate.question(txn("Shop 98000 00030", method = card)))
        assertNull(AiPrivacyGate.question(txn("Pay ravi@ybl", method = card)))
        assertNull(AiPrivacyGate.question(txn("A1 2 3", method = card)))
        assertNull(AiPrivacyGate.question(txn("x".repeat(61), method = card)))
    }
}
