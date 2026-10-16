package dev.adminos.api.domain.financial.links

import dev.adminos.api.domain.financial.PaymentMethod
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

class TransferDetectorTest {
    private val d = TransferDetector()
    private val ist = ZoneId.of("Asia/Kolkata")
    private val user = UUID.randomUUID()
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDate.of(2026, 10, day).atTime(LocalTime.of(h, m)).atZone(ist).toInstant()

    private fun t(type: TransactionType, last4: String?, day: Int, h: Int, m: Int = 0, text: String = "", source: String = "sms",
                  amount: String = "5000.00", method: PaymentMethod? = null) =
        Transaction(userId = user, sourceType = source, type = type, amount = BigDecimal(amount), accountLast4 = last4,
            merchantRaw = text, paymentMethod = method, transactedAt = at(day, h, m))

    @Test
    fun `card bill payments are recognised`() {
        for (text in listOf("CREDIT CARD PAYMENT HDFC", "CC PAYMENT 4321", "BILLDESK*HDFC CARD", "UPI-CRED-cred.club@axisb-123", "AUTOPAY SBI CARD", "Card bill payment")) {
            assertTrue(d.isCardBillPayment(t(TransactionType.DEBIT, "1234", 1, 9, text = text)), text)
        }
    }

    @Test
    fun `look-alikes are not card bill payments`() {
        assertFalse(d.isCardBillPayment(t(TransactionType.DEBIT, "1234", 1, 9, text = "SALARY CREDITED")), "CREDITED is not CRED")
        assertFalse(d.isCardBillPayment(t(TransactionType.DEBIT, "1234", 1, 9, text = "INCRED FINANCE EMI")))
        assertFalse(d.isCardBillPayment(t(TransactionType.DEBIT, "1234", 1, 9, text = "AMAZON", method = PaymentMethod.CARD_CREDIT)), "a purchase on a card")
        assertFalse(d.isCardBillPayment(t(TransactionType.CREDIT, "1234", 1, 9, text = "CREDIT CARD PAYMENT RECEIVED")), "only debits pay bills")
    }

    @Test
    fun `tight sms pair with an imps narration links automatically`() {
        val out = t(TransactionType.DEBIT, "1234", 1, 9, 0, text = "IMPS to A/c XX5678")
        val inn = t(TransactionType.CREDIT, "5678", 1, 9, 2)
        val r = assertIs<TransferDecision.Pair>(d.decide(inn, listOf(out)))
        assertTrue(r.auto); assertEquals(4 + 3 + 3 + 5, r.score)
    }

    @Test
    fun `a pair without hints is only suggested`() {
        val r = assertIs<TransferDecision.Pair>(d.decide(t(TransactionType.CREDIT, "5678", 1, 9, 5), listOf(t(TransactionType.DEBIT, "1234", 1, 9, 0))))
        assertFalse(r.auto); assertEquals(7, r.score)
    }

    @Test
    fun `statement row naming the other account links automatically`() {
        val out = t(TransactionType.DEBIT, "1234", 1, 12, text = "NEFT DR-TO OWN A/C XXXX5678", source = "statement")
        val r = assertIs<TransferDecision.Pair>(d.decide(t(TransactionType.CREDIT, "5678", 1, 12, source = "statement"), listOf(out)))
        assertTrue(r.auto)
    }

    @Test
    fun `same account, different amount, or too far apart never pair`() {
        val out = t(TransactionType.DEBIT, "1234", 1, 9, text = "IMPS")
        assertEquals(TransferDecision.None, d.decide(t(TransactionType.CREDIT, "1234", 1, 9, 1), listOf(out)))
        assertEquals(TransferDecision.None, d.decide(t(TransactionType.CREDIT, "5678", 1, 9, 1, amount = "4999.00"), listOf(out)))
        assertEquals(TransferDecision.None, d.decide(t(TransactionType.CREDIT, "5678", 4, 9), listOf(out)))
        assertEquals(TransferDecision.None, d.decide(t(TransactionType.CREDIT, null, 1, 9, 1), listOf(out)), "unknown account")
    }

    @Test
    fun `two equally good counterparts are suggested, not auto-linked`() {
        val a = t(TransactionType.DEBIT, "1234", 1, 9, 0, text = "IMPS")
        val b = t(TransactionType.DEBIT, "4444", 1, 9, 0, text = "IMPS")
        assertFalse(assertIs<TransferDecision.Pair>(d.decide(t(TransactionType.CREDIT, "5678", 1, 9, 1), listOf(a, b))).auto)
    }

    @Test
    fun `ATM and cash withdrawals are recognised (D-42)`() {
        for (text in listOf("ATM WDL HDFC MG ROAD", "ATW-512345XXXXXX1234-S1ANBG01", "NWD-512345XXXXXX1234-SBI ATM", "CASH WDL 12345", "Cash withdrawal at branch", "Rs 2000 withdrawn at ATM")) {
            assertTrue(d.isCashWithdrawal(t(TransactionType.DEBIT, "1234", 1, 9, text = text, method = PaymentMethod.CARD_DEBIT)), text)
        }
        for (text in listOf("ATMOSPHERE CAFE", "BATMAN STORE", "CASHFREE*SWIGGY", "ZOMATO")) {
            assertFalse(d.isCashWithdrawal(t(TransactionType.DEBIT, "1234", 1, 9, text = text)), text)
        }
        assertFalse(d.isCashWithdrawal(t(TransactionType.CREDIT, "1234", 1, 9, text = "ATM CASH DEPOSIT")), "credits are not withdrawals")
        assertFalse(d.isCashWithdrawal(t(TransactionType.DEBIT, null, 1, 9, text = "ATM", source = "manual")), "typed cash is spending")
    }
}
