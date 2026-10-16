package dev.adminos.api.domain.financial.links

import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RefundReversalDetectorTest {
    private val d = RefundReversalDetector()
    private val ist = ZoneId.of("Asia/Kolkata")
    private val user = UUID.randomUUID()
    private fun at(day: Int, h: Int = 10) = LocalDate.of(2026, 10, day).atTime(LocalTime.of(h, 0)).atZone(ist).toInstant()

    private fun debit(day: Int, amount: String = "999.00", merchant: String = "Amazon", raw: String = "AMAZON PAY", ref: String? = null) =
        Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal(amount), merchantName = merchant, merchantRaw = raw,
            accountLast4 = "1234", paymentRef = ref, transactedAt = at(day))
    private fun credit(day: Int, amount: String = "999.00", merchant: String? = "Amazon", raw: String = "AMAZON REFUND", ref: String? = null, last4: String? = "1234") =
        Transaction(userId = user, type = TransactionType.CREDIT, amount = BigDecimal(amount), merchantName = merchant, merchantRaw = raw,
            accountLast4 = last4, paymentRef = ref, transactedAt = at(day, 15))

    @Test
    fun `failed payment reversed the same day is an automatic reversal`() {
        val r = assertIs<RefundDecision.Link>(d.decide(credit(1, raw = "REV-UPI-SWIGGY", merchant = "Swiggy"), listOf(debit(1, merchant = "Swiggy"))))
        assertEquals(LinkRelation.REVERSAL, r.relation); assertTrue(r.auto)
    }

    @Test
    fun `same payment reference means reversal even without a keyword`() {
        val r = assertIs<RefundDecision.Link>(d.decide(credit(3, raw = "UPI CR", merchant = null, ref = "123456789012"), listOf(debit(1, ref = "123456789012"))))
        assertEquals(LinkRelation.REVERSAL, r.relation); assertTrue(r.auto)
    }

    @Test
    fun `same-day same-amount credit without evidence is only suggested`() {
        val r = assertIs<RefundDecision.Link>(d.decide(credit(1, raw = "UPI CR", merchant = "Ravi"), listOf(debit(1, merchant = "Swiggy"))))
        assertEquals(LinkRelation.REVERSAL, r.relation); assertFalse(r.auto)
    }

    @Test
    fun `full refund from the same merchant is linked automatically`() {
        val r = assertIs<RefundDecision.Link>(d.decide(credit(20), listOf(debit(1))))
        assertEquals(LinkRelation.REFUND, r.relation); assertTrue(r.auto)
    }

    @Test
    fun `partial refund with a refund narration is linked but a bare partial credit is not`() {
        assertTrue(assertIs<RefundDecision.Link>(d.decide(credit(10, amount = "200.00"), listOf(debit(1)))).auto)
        val weak = d.decide(credit(10, amount = "200.00", raw = "AMAZON CR"), listOf(debit(1)))
        assertEquals(RefundDecision.None, weak, "partial, no refund narration: 3 merchant + 1 account = 4 < 5")
    }

    @Test
    fun `refunds never exceed the original, including earlier refunds`() {
        val original = debit(1)
        assertEquals(RefundDecision.None, d.decide(credit(5, amount = "1200.00"), listOf(original)))
        assertEquals(RefundDecision.None, d.decide(credit(5, amount = "500.00"), listOf(original), refundedSoFar = mapOf(original.id to BigDecimal("600.00"))))
    }

    @Test
    fun `unrelated credits and stale refunds are not linked`() {
        assertEquals(RefundDecision.None, d.decide(credit(5, amount = "50000.00", merchant = "Acme", raw = "SALARY"), listOf(debit(1))))
        assertEquals(RefundDecision.None, d.decide(credit(5, merchant = "Flipkart", raw = "FLIPKART REFUND"), listOf(debit(1))), "different merchant")
        val late = credit(1).copy(transactedAt = at(1).plus(java.time.Duration.ofDays(100)))
        assertEquals(RefundDecision.None, d.decide(late, listOf(debit(1))))
        assertEquals(RefundDecision.None, d.decide(credit(5, last4 = "9999"), listOf(debit(1))), "different account")
    }

    @Test
    fun `works when the debit arrives after its refund (statement order)`() {
        val r = assertIs<RefundDecision.Link>(d.decide(debit(1), listOf(credit(20))))
        assertEquals(LinkRelation.REFUND, r.relation)
    }
}
