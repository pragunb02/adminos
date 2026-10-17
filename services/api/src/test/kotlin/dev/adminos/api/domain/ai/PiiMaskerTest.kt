package dev.adminos.api.domain.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-702, research §2.4: personal data in Indian formats is masked before anything reaches a model. Synthetic data only. */
class PiiMaskerTest {

    private fun mask(text: String) = PiiMasker.mask(text)

    @Test
    fun `card and account numbers keep only their last 4 digits`() {
        assertEquals("Card XX1234 used", mask("Card 4111 1111 1111 1234 used").text)
        assertEquals("A/c XX4321 debited", mask("A/c 50100012344321 debited").text)
        assertEquals("XX0005", mask("1234-5678-9000-0005").text)
        assertEquals(setOf(PiiKind.CARD_OR_ACCOUNT), mask("A/c 50100012344321").found)
    }

    @Test
    fun `already masked numbers and short numbers are left alone`() {
        for (clean in listOf("A/c XX1234 debited", "Card **5678", "INR 2,499.00 at Swiggy", "OTP 482913", "Order 12345678", "Steam Purchase")) {
            val m = mask(clean)
            assertEquals(clean, m.text)
            assertTrue(m.isClean, clean)
        }
    }

    @Test
    fun `Indian mobile numbers become a phone marker`() {
        for (phone in listOf("9800000012", "98000 00012", "+91 98000 00012", "+91-9800000012", "919800000012", "09800000012")) {
            assertEquals("call <PHONE> now", mask("call $phone now").text, phone)
        }
        assertEquals(setOf(PiiKind.PHONE), mask("8800000034").found)
    }

    @Test
    fun `emails and UPI IDs are masked, keeping only the UPI provider`() {
        assertEquals("from <EMAIL>", mask("from rohit.sharma@example.co.in").text)
        assertEquals("paid <VPA:oksbi> for tea", mask("paid rohit.sharma92@oksbi for tea").text)
        assertEquals("UPI/<VPA:ybl>/Ref", mask("UPI/9800000012@ybl/Ref").text)
        assertEquals(setOf(PiiKind.EMAIL, PiiKind.VPA), mask("a.b@example.com and cd@paytm").found)
    }

    @Test
    fun `Aadhaar-like numbers and PANs become an id marker`() {
        assertEquals("id <ID>", mask("id 2345 6789 0123").text)
        assertEquals("id <ID>", mask("id 234567890123").text)
        assertEquals("PAN <ID>", mask("PAN ABCDE1234F").text)
        assertEquals(setOf(PiiKind.NATIONAL_ID), mask("abcde1234f").found)
    }

    @Test
    fun `a masked result never prints its text`() {
        assertFalse(mask("9800000012").toString().contains("<PHONE>"))
    }

    @Test
    fun `numbers in other scripts or with other separators are masked too (AOS-702 review B1)`() {
        val fullwidth = "\uFF19\uFF18\uFF10\uFF10\uFF10\uFF10\uFF10\uFF10\uFF11\uFF12" // a mobile number in fullwidth digits
        val devanagari = "\u096F\u096E\u0966\u0966\u0966\u0966\u0966\u0966\u0967\u0968" // the same in Devanagari digits
        assertEquals("<PHONE>", mask(fullwidth).text)
        assertEquals("<PHONE>", mask(devanagari).text)
        assertEquals("XX9012", mask("4111.1111/1111_9012").text)
        assertEquals("XX9012", mask("4111  1111  1111  9012").text)
        assertEquals("<ID>", mask("ABCDE\uFF11234F").text) // a PAN with a fullwidth digit
    }
}
