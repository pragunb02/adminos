package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.domain.financial.mandates.MandateFrequency
import dev.adminos.api.domain.financial.mandates.MandateNoticeKind
import dev.adminos.api.domain.financial.mandates.MandateRail
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Mandate SMS (AOS-502). Samples are SYNTHETIC approximations of common formats (templates vary by
 * bank and change); check against a few of your own messages before relying on them.
 */
class MandateSmsParserTest {
    private val registry = SmsParserRegistry()
    private fun parse(text: String, sender: String = "AX-HDFCBK-S") = registry.parse(SmsMessage(sender, text, Instant.parse("2026-10-16T03:42:00Z")))
    private fun notice(text: String, sender: String = "AX-HDFCBK-S") = assertIs<SmsParseResult.Mandate>(parse(text, sender)).notice

    @Test
    fun `a UPI AutoPay set-up gives payee, amount, frequency, UMN and bank`() {
        val n = notice("Your UPI AutoPay mandate for NETFLIX of Rs 649.00 Monthly has been successfully created. UMN: 7a8b9c0d1e@hdfcbank - HDFC Bank")
        assertEquals(MandateNoticeKind.CREATED, n.kind)
        assertEquals(MandateRail.UPI_AUTOPAY, n.rail)
        assertEquals("NETFLIX", n.payee)
        assertEquals(BigDecimal("649.00"), n.amount)
        assertEquals(MandateFrequency.MONTHLY, n.frequency)
        assertEquals("7a8b9c0d1e@hdfcbank", n.umn)
        assertEquals("hdfc", n.bank)
    }

    @Test
    fun `the pre-debit notice is a mandate notice with its date, not a dropped upcoming payment`() {
        val n = notice("Rs 649.00 will be debited from your A/c XX1234 on 10-11-2026 towards NETFLIX for UPI AutoPay. UMN 7a8b9c0d1e@hdfcbank")
        assertEquals(MandateNoticeKind.PRE_DEBIT, n.kind)
        assertEquals("NETFLIX", n.payee)
        assertEquals(LocalDate.of(2026, 11, 10), n.debitOn)
        assertEquals(BigDecimal("649.00"), n.amount)
    }

    @Test
    fun `e-NACH registration with UMRN and validity`() {
        val n = notice("e-NACH mandate registered in favour of BAJAJ FINANCE LTD for Rs 5,400.00 Monthly, valid till 05 Mar 2028. UMRN HDFC7000000012345", "VM-ICICIB")
        assertEquals(MandateRail.NACH, n.rail)
        assertEquals("BAJAJ FINANCE LTD", n.payee)
        assertEquals(BigDecimal("5400.00"), n.amount)
        assertEquals("HDFC7000000012345", n.umn)
        assertEquals(LocalDate.of(2028, 3, 5), n.validUntil)
        assertEquals("icici", n.bank)
    }

    @Test
    fun `a card standing instruction, a pause, a resume and a revoke`() {
        val si = notice("Standing instruction set up on your HDFC Bank Credit Card XX4521 for Amazon Prime of Rs 1,499.00 yearly.")
        assertEquals(MandateRail.CARD_SI, si.rail); assertEquals("Amazon Prime", si.payee); assertEquals(MandateFrequency.YEARLY, si.frequency)
        assertEquals(MandateNoticeKind.PAUSED, notice("Your UPI AutoPay mandate for SPOTIFY has been paused.").kind)
        assertEquals(MandateNoticeKind.RESUMED, notice("Your UPI AutoPay mandate for SPOTIFY has been resumed.").kind)
        val revoked = notice("UPI AutoPay mandate for NETFLIX has been revoked successfully. UMN: 7a8b9c0d1e@hdfcbank")
        assertEquals(MandateNoticeKind.REVOKED, revoked.kind)
        assertEquals("NETFLIX", revoked.payee)
    }

    @Test
    fun `a mandate debit that happened is a transaction, marked as a mandate debit with its UMN`() {
        val t = assertIs<SmsParseResult.Transaction>(parse("Rs.649.00 debited from A/c XX1234 on 10-11-26 towards UPI Mandate NETFLIX. UMN 7a8b9c0d1e@hdfcbank. Not you? Call 18002586161")).parsed
        assertEquals(mapOf("mandate" to "true", "umn" to "7a8b9c0d1e@hdfcbank"), t.markers)
        val plain = assertIs<SmsParseResult.Transaction>(parse("Sent Rs.249.00 From HDFC Bank A/C *1234 To SWIGGY On 16/10/26 Ref 123456789012")).parsed
        assertEquals(emptyMap(), plain.markers)
    }

    @Test
    fun `OTPs and failures stay dropped, even about mandates`() {
        assertEquals("one-time password", assertIs<SmsParseResult.Ignored>(parse("123456 is your OTP to create UPI AutoPay mandate for NETFLIX")).reason)
        assertEquals("failed or declined transaction", assertIs<SmsParseResult.Ignored>(parse("Your UPI AutoPay mandate creation for NETFLIX has failed")).reason)
        assertEquals("upcoming payment notice", assertIs<SmsParseResult.Ignored>(parse("Your HDFC credit card bill of Rs 4,560 is due on 20-10-2026")).reason)
    }

    @Test
    fun `messages without a payee or not about mandates aren't notices`() {
        assertNull(MandateSmsParser.parse("Your mandate has been created."))
        assertNull(MandateSmsParser.parse("Your account statement is ready."))
        assertNull(MandateSmsParser.parse("Mandate for for Rs 100 created"))
    }

    @Test
    fun `dates are read day first, two-digit years as 20xx, nonsense as nothing`() {
        assertEquals(LocalDate.of(2026, 11, 10), MandateSmsParser.date("10/11/2026"))
        assertEquals(LocalDate.of(2026, 11, 10), MandateSmsParser.date("10-11-26"))
        assertEquals(LocalDate.of(2026, 11, 10), MandateSmsParser.date("10 Nov 2026"))
        assertEquals(LocalDate.of(2026, 11, 10), MandateSmsParser.date("10 November 2026"))
        assertNull(MandateSmsParser.date("31/02/2026"))
        assertNull(MandateSmsParser.date("soon"))
        assertEquals(MandateRail.UNKNOWN, MandateSmsParser.rail("mandate for X"))
    }
}
