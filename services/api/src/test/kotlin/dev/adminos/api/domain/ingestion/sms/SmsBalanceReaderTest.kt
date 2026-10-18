package dev.adminos.api.domain.ingestion.sms

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** AOS-720: what the reader takes from a balance phrase, beyond the eval set's cases (src/test/resources/balances/sms.tsv). */
class SmsBalanceReaderTest {
    private fun found(text: String) = assertIs<SmsBalanceResult.Found>(SmsBalanceReader.read(text), text).balance
    private fun reason(text: String) = assertIs<SmsBalanceResult.NotFound>(SmsBalanceReader.read(text), text).reason

    @Test
    fun `as-on dates are read in numeric and month-name forms, two- or four-digit years`() {
        assertEquals(LocalDate.of(2026, 10, 14), found("Avl Bal Rs 1.00 in A/c XX1234 as on 14-10-26").asOn)
        assertEquals(LocalDate.of(2026, 10, 14), found("Avl Bal Rs 1.00 in A/c XX1234 as of 14/Oct/2026").asOn)
        assertEquals(LocalDate.of(2026, 1, 5), found("Avl Bal Rs 1.00 in A/c XX1234 as on 5.1.2026").asOn)
        assertEquals(null, found("Avl Bal Rs 1.00 in A/c XX1234 as on 31-02-26").asOn, "not a date: no date, never a guess")
        assertEquals(null, found("Avl Bal Rs 1.00 in A/c XX1234 as on 14-Foo-26").asOn)
        assertEquals(null, found("Avl Bal Rs 1.00 in A/c XX1234 on 14-10-26").asOn, "a plain 'on' date is the transaction's")
    }

    @Test
    fun `a date or a time after the label is never read as the figure`() {
        assertEquals(BigDecimal("5000.00"), found("Bal as on 15.10.2026 at 10.30 is 5,000.00 for A/c XX1234").amount)
        assertEquals(reason("Avl Bal as on 15.10.2026 for A/c XX1234"), NoBalance.NONE)
    }

    @Test
    fun `the figure must follow the label closely, with only connecting words`() {
        assertEquals(NoBalance.NONE, reason("Avl Bal will be shown later. A/c XX1234 debited Rs 500.00"))
        assertEquals(NoBalance.NONE, reason("Avl Bal ${"x".repeat(80)} Rs 500.00 A/c XX1234"))
    }

    @Test
    fun `the longest account number gives its last 4 digits, a masked 3 stays 3`() {
        assertEquals("5678", found("Avl Bal Rs 1.00 in Account 0012345678").accountHint)
        assertEquals("123", found("Acct XX123 Avl Bal Rs 1.00").accountHint)
        assertEquals("1234", found("Debit Card XX9876 used. A/c XX1234 Avl Bal Rs 1.00").accountHint, "the account, not the card")
    }

    @Test
    fun `balance phrases inside a merchant name are spotted`() {
        assertTrue(SmsBalanceReader.inMerchant("AVL BAL RS 99999.00"))
        assertFalse(SmsBalanceReader.inMerchant("GLOBAL BAZAAR"))
        assertFalse(SmsBalanceReader.inMerchant("BAL TRADERS"))
        assertFalse(SmsBalanceReader.inMerchant(null))
    }

    @Test
    fun `huge and repetitive hostile text is read quickly and fails closed`() {
        val start = System.nanoTime()
        val texts = listOf(
            "Avl Bal " + "is of in at on ".repeat(300) + "Rs 1.00 A/c XX1234",
            ("Avl Bal: XX1234 " + "-".repeat(50)).repeat(60),
            "Bal ".repeat(1000) + "Rs 1.00",
            "A/c XX" + "1".repeat(1500) + " Avl Bal Rs 9.00"
        )
        val results = texts.map { SmsBalanceReader.read(it) }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3_000, "reading hostile text took too long")
        assertIs<SmsBalanceResult.NotFound>(results[0])
        assertIs<SmsBalanceResult.NotFound>(results[2], "no account")
        assertEquals("1111", (results[3] as SmsBalanceResult.Found).balance.accountHint)
    }

    @Test
    fun `nothing printed holds the figure or the account`() {
        val b = found("Avl Bal Rs 4,321.00 in A/c XX8642")
        assertFalse("4321" in b.toString() || "8642" in b.toString())
        val trusted = SmsBalanceCapture.Trusted("hdfc", b)
        assertFalse("4321" in trusted.toString())
    }

    @Test
    fun `the gate trusts only known bank senders and readable messages`() {
        val registry = SmsParserRegistry()
        val gate = SmsBalanceGate(registry)
        val text = "Rs.500.00 debited from a/c **1234 on 15-10-26. Avl bal:INR 12,000.00"
        fun read(sender: String, t: String = text) = gate.read(SmsMessage(sender, t, Instant.EPOCH), registry.parse(SmsMessage(sender, t, Instant.EPOCH)))
        assertIs<SmsBalanceCapture.Trusted>(read("AX-HDFCBK-S")).also { assertEquals("hdfc", it.bankCode) }
        assertEquals(SmsBalanceCapture.Skipped(NoBalance.UNTRUSTED_SENDER), read("AX-NEWBNK-S"))
        assertEquals(SmsBalanceCapture.Skipped(NoBalance.UNTRUSTED_SENDER), read("not a sender!"))
        assertEquals(SmsBalanceCapture.Skipped(NoBalance.NOT_READ), read("AX-HDFCBK-S", "Your e-mandate for NETFLIX of Rs 649.00 is set up. UMN abc123@hdfc. Avl Bal Rs 1,000.00 A/c XX1234"))
        assertEquals(null, registry.knownBankOf("AX-HDFCBK-P"))
        assertEquals("sbi", registry.knownBankOf("JD-SBIUPI-T"))
    }
}
