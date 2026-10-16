package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.ingestion.events.IngestDirection
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Samples are SYNTHETIC approximations of common Indian bank SMS formats (not real messages; bank
 * templates change). Before relying on a parser, check it locally against a few of your real SMS.
 */
class SmsParserRegistryTest {

    private val registry = SmsParserRegistry()
    private fun sms(sender: String, text: String) = SmsMessage(sender, text, Instant.parse("2026-10-16T03:42:00Z"))
    private fun txn(sender: String, text: String) = assertIs<SmsParseResult.Transaction>(registry.parse(sms(sender, text))).parsed
    private fun ignored(sender: String, text: String) = assertIs<SmsParseResult.Ignored>(registry.parse(sms(sender, text))).reason
    private fun money(s: String) = BigDecimal(s).setScale(2)

    @Test
    fun `HDFC UPI sent`() {
        val t = txn("AX-HDFCBK-S", "Sent Rs.249.00 From HDFC Bank A/C *1234 To SWIGGY On 16/10/26 Ref 123456789012 Not You? Call 18002586161/SMS BLOCK UPI to 7308080808")
        assertEquals(IngestDirection.DEBIT, t.direction); assertEquals(money("249.00"), t.amount)
        assertEquals("1234", t.accountHint); assertEquals("SWIGGY", t.merchantRaw); assertEquals(PaymentMethod.UPI, t.paymentMethod)
        assertEquals("hdfc", t.bankCode)
    }

    @Test
    fun `HDFC card spend`() {
        val t = txn("VM-HDFCBK-T", "Spent Rs.1,299 On HDFC Bank Card 5678 At AMAZON On 2026-10-16:09:12:00 Not You? To Block+Reissue Call 18002323232")
        assertEquals(IngestDirection.DEBIT, t.direction); assertEquals(money("1299"), t.amount)
        assertEquals("5678", t.accountHint); assertEquals("AMAZON", t.merchantRaw); assertEquals(PaymentMethod.CARD_DEBIT, t.paymentMethod)
    }

    @Test
    fun `HDFC salary credit via VPA`() {
        val t = txn("JD-HDFCBK-S", "Credit Alert! Rs.50,000.00 credited to HDFC Bank A/c XX1234 on 16-10-26 from VPA employer@hdfcbank (UPI 123456789012)")
        assertEquals(IngestDirection.CREDIT, t.direction); assertEquals(money("50000"), t.amount)
        assertEquals("employer@hdfcbank", t.upiVpa); assertEquals(PaymentMethod.UPI, t.paymentMethod)
    }

    @Test
    fun `SBI UPI debit without currency symbol`() {
        val t = txn("AD-SBIUPI-S", "Dear UPI user A/C X1234 debited by 249.0 on date 16Oct26 trf to SWIGGY Refno 123456789012. If not u? call 1800111109. -SBI")
        assertEquals(IngestDirection.DEBIT, t.direction); assertEquals(money("249.00"), t.amount)
        assertEquals("SWIGGY", t.merchantRaw); assertEquals("sbi", t.bankCode)
    }

    @Test
    fun `ICICI UPI debit with 3-digit mask keeps no account hint`() {
        val t = txn("VK-ICICIT-S", "ICICI Bank Acct XX234 debited for Rs 249.00 on 16-Oct-26; SWIGGY credited. UPI:123456789012. Call 18002662 for dispute.")
        assertEquals(IngestDirection.DEBIT, t.direction, "debited appears before credited"); assertEquals(money("249.00"), t.amount)
        assertNull(t.accountHint); assertEquals("SWIGGY", t.merchantRaw)
    }

    @Test
    fun `ICICI card spend ignores the available limit amount`() {
        val t = txn("VM-ICICIB-T", "INR 1,299.00 spent using ICICI Bank Card XX1234 on 16-Oct-26 on AMAZON. Avl Limit: INR 2,00,000.00. If not you, call 1800 2662")
        assertEquals(money("1299.00"), t.amount); assertEquals("AMAZON", t.merchantRaw); assertEquals("1234", t.accountHint)
    }

    @Test
    fun `Axis multi-line UPI narration`() {
        val t = txn("AX-AXISBK-S", "INR 249.00 debited\nA/c no. XX1234\n16-10-26, 09:12:11\nUPI/P2M/123456789012/SWIGGY\nNot you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank")
        assertEquals(IngestDirection.DEBIT, t.direction); assertEquals(money("249.00"), t.amount)
        assertEquals("1234", t.accountHint); assertEquals("SWIGGY", t.merchantRaw)
    }

    @Test
    fun `Kotak send to VPA`() {
        val t = txn("BZ-KOTAKB-S", "Sent Rs.249.00 from Kotak Bank AC X1234 to swiggy@icici on 16-10-26.UPI Ref 123456789012. Not you, https://kotak.com/KBANKT/Fraud")
        assertEquals("swiggy@icici", t.upiVpa); assertEquals("swiggy@icici", t.merchantRaw); assertEquals("kotak", t.bankCode)
    }

    @Test
    fun `unknown bank header falls back to the generic parser`() {
        val t = txn("AX-YESBNK-S", "Rs.500.00 debited from A/c XX9876 on 16-10-26 to ZOMATO. UPI Ref 123")
        assertNull(t.bankCode); assertEquals(money("500.00"), t.amount)
    }

    @Test
    fun `drop rules - otp, promo, declined, collect request, reminders, balance, personal numbers`() {
        assertTrue("one-time" in ignored("AX-HDFCBK-T", "OTP is 123456 for txn of Rs.249.00 at SWIGGY. Do not share."))
        assertTrue("promotional" in ignored("AX-HDFCBK-P", "Get Rs.500 cashback on spends! Apply now."))
        assertTrue("failed" in ignored("AX-HDFCBK-S", "Txn of Rs.249.00 on HDFC Bank Card 1234 at SWIGGY was declined."))
        assertTrue("request" in ignored("AX-HDFCBK-S", "SWIGGY has requested money Rs.249.00 from you on UPI."))
        assertTrue("upcoming" in ignored("AX-HDFCBK-S", "Rs.12,500 will be debited from A/c XX1234 on 20-10-26 for your EMI."))
        assertTrue("balance" in ignored("AX-HDFCBK-S", "Available Balance in A/c XX1234 is Rs.45,000.00 as on 16-10-26."))
        assertTrue("registered" in ignored("+919876543210", "Sent Rs.249.00 To SWIGGY"))
        assertTrue("recognised" in ignored("AX-HDFCBK-S", "Your statement for Oct is ready."))
    }

    @Test
    fun `SmsMessage never prints its text`() {
        val m = SmsMessage("AX-HDFCBK-S", "Sent Rs.249.00 secret-merchant", Instant.EPOCH)
        assertTrue("secret-merchant" !in m.toString())
    }
}
