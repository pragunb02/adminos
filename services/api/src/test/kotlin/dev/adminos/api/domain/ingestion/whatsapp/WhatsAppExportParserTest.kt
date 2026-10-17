package dev.adminos.api.domain.ingestion.whatsapp

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhatsAppExportParserTest {
    private val nnbsp = "\u202F"
    private val lrm = "\u200E"

    @Test
    fun `an iPhone export with seconds, AM PM, the narrow space and LRM marks is read`() {
        val chat = """
            [16/10/26, 9:12:03${nnbsp}AM] HDFC Bank: ${lrm}Messages and calls are end-to-end encrypted.
            [16/10/26, 9:15:44${nnbsp}PM] HDFC Bank: Sent Rs.249.00 From HDFC Bank A/C *1234 To SWIGGY
            [17/10/26, 12:05:00${nnbsp}AM] HDFC Bank: Second message
        """.trimIndent()
        val export = WhatsAppExportParser.parse(chat)
        assertEquals(3, export.messages.size)
        assertEquals(LocalDateTime.of(2026, 10, 16, 21, 15, 44), export.messages[1].at)
        assertEquals("HDFC Bank", export.messages[1].sender)
        assertEquals("Sent Rs.249.00 From HDFC Bank A/C *1234 To SWIGGY", export.messages[1].text)
        assertEquals(LocalDateTime.of(2026, 10, 17, 0, 5), export.messages[2].at, "12 AM is midnight")
    }

    @Test
    fun `an Android export in 24-hour and 12-hour time is read, and system lines are counted not kept`() {
        val chat = """
            16/10/2026, 09:12 - Messages and calls are end-to-end encrypted. No one outside of this chat can read them.
            16/10/2026, 21:40 - ICICI Bank: Rs 500.00 debited
            16/10/26, 9:41 pm - ICICI Bank: Rs 600.00 debited
        """.trimIndent()
        val export = WhatsAppExportParser.parse(chat)
        assertEquals(listOf(LocalDateTime.of(2026, 10, 16, 21, 40), LocalDateTime.of(2026, 10, 16, 21, 41)), export.messages.map { it.at })
        assertEquals(1, export.systemLines)
    }

    @Test
    fun `lines without a timestamp continue the message above, and noise before the first message is dropped`() {
        val chat = "header noise\n16/10/26, 09:12 - Axis Bank: Rs 100 debited\nfrom A/c XX1234\r\n\r\nThanks\n16/10/26, 09:13 - Axis Bank: next"
        val export = WhatsAppExportParser.parse(chat)
        assertEquals(listOf("Rs 100 debited\nfrom A/c XX1234\n\nThanks", "next"), export.messages.map { it.text })
    }

    @Test
    fun `day and month order comes from the file, US order is detected, and an ambiguous file is day first`() {
        val us = WhatsAppExportParser.parse("10/16/26, 9:12 AM - Bank: a\n10/05/26, 9:12 AM - Bank: b")
        assertFalse(us.dayFirst)
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 12), us.messages[1].at)

        val ambiguous = WhatsAppExportParser.parse("05/10/26, 09:12 - Bank: a")
        assertTrue(ambiguous.dayFirst)
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 12), ambiguous.messages.single().at)
    }

    @Test
    fun `impossible dates and times are counted as unreadable, and their continuation lines are not glued elsewhere`() {
        val export = WhatsAppExportParser.parse("31/02/26, 09:12 - Bank: bad date\nmore\n16/10/26, 25:00 - Bank: bad time\n16/10/26, 09:12 - Bank: good")
        assertEquals(2, export.unreadableLines)
        assertEquals(listOf("good"), export.messages.map { it.text })
    }

    @Test
    fun `dotted dates, empty messages and an empty file are handled`() {
        assertEquals(1, WhatsAppExportParser.parse("16.10.26, 09:12 - Bank: hi\n16.10.26, 09:13 - Bank: ").messages.size)
        val empty = WhatsAppExportParser.parse("")
        assertEquals(0, empty.messages.size)
        assertEquals(0, empty.unreadableLines)
    }

    @Test
    fun `a chat message never prints its text`() {
        val m = WhatsAppExportParser.parse("16/10/26, 09:12 - Bank: secret text").messages.single()
        assertFalse("secret" in m.toString())
    }

    @Test
    fun `bank senders are matched by name, look-alikes go to the generic parser, and people are never banks`() {
        assertEquals(BankSenders.Bank("hdfc"), BankSenders.match("HDFC Bank"))
        assertEquals(BankSenders.Bank("sbi"), BankSenders.match("State Bank of India"))
        assertEquals(BankSenders.Bank("kotak"), BankSenders.match("Kotak811"))
        assertEquals(BankSenders.UnknownBank, BankSenders.match("Yes Bank"))
        assertEquals(BankSenders.UnknownBank, BankSenders.match("OneCard Credit Card"))
        assertEquals(null, BankSenders.match("Ravi"))
        assertEquals(null, BankSenders.match("+91 98765 43210"))
        assertEquals(null, BankSenders.match("Taxis Mumbai"), "axis inside another word is not Axis Bank")
    }
}
