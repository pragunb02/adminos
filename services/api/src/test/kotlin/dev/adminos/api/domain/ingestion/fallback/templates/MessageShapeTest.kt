package dev.adminos.api.domain.ingestion.fallback.templates

import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.fallback.MessageAnswer
import dev.adminos.api.domain.ingestion.fallback.MessageQuestion
import dev.adminos.api.domain.ingestion.fallback.RedactedMessage
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageShapeTest {
    private val on = LocalDate.of(2026, 10, 16)
    private fun redacted(text: String) = (MessageQuestion.of(text, on).redacted as RedactedMessage.Ready).text
    private fun answer(amount: String = "249.00", last4: String? = "4321", merchant: String? = "ZEPTO", date: LocalDate? = on, direction: IngestDirection = IngestDirection.DEBIT) =
        MessageAnswer.Transaction(direction, BigDecimal(amount), last4, merchant, date)
    private fun shape(text: String, a: MessageAnswer.Transaction = answer()) = MessageShapes.of(redacted(text), a)

    @Test
    fun `every value becomes a slot and only the wording is left`() {
        assertEquals(
            "Card XX<ACCT> charged Rs.<AMT> at <M> on <DATE>. Avl Lmt Rs <BAL>. Ref <N>",
            shape("Card XX4321 charged Rs.249.00 at ZEPTO on 16-10-26. Avl Lmt Rs 45,000.00. Ref 7788")?.text
        )
        assertEquals("<AMT> INR debited from A/c XX<ACCT> on <DATE>; UPI to <M>. Bal: <BAL> INR",
            shape("1,250.50 INR debited from A/c XX4321 on 16 Oct 2026; UPI to AMAZON PAY. Bal: 8,750.00 INR", answer("1250.50", merchant = "AMAZON PAY"))?.text)
    }

    @Test
    fun `masked personal data stays masked and other account endings are plain numbers`() {
        val s = shape("Rs.249.00 Dr. from A/c 5021 0042 9988 4321 to ZEPTO, a/c XX9999 on 16-10-26. Call 98000 00012", answer())?.text
        assertEquals("Rs.<AMT> Dr. from A/c XX<ACCT> to <M>, a/c XX<N> on <DATE>. Call <PHONE>", s)
    }

    @Test
    fun `a greeting's name and a repeated payee are slots`() {
        assertEquals("Dear <NAME>, Rs <AMT> Cr. to A/c XX<ACCT> from <M>. Thanks, <M>.",
            shape("Dear Asha, Rs 500.00 Cr. to A/c XX4321 from RAHUL K. Thanks, rahul k.", answer("500", merchant = "RAHUL K", date = null, direction = IngestDirection.CREDIT))?.text)
    }

    @Test
    fun `no digit, amount, payee or date survives in a shape`() {
        val s = shape("Card XX4321 charged Rs.249.00 at ZEPTO on 16-10-26 10:15. Avl Lmt Rs 45,000.00")!!.text
        assertFalse(s.any(Char::isDigit) || "ZEPTO" in s || "249" in s, s)
        assertFailsWith<IllegalArgumentException> { MessageShape("Rs <AMT> on 16") }
        assertFailsWith<IllegalArgumentException> { MessageShape("Rs <BAL>") }
        assertFailsWith<IllegalArgumentException> { MessageShape("<AMT>" + "x".repeat(MessageShape.MAX_LENGTH)) }
    }

    @Test
    fun `a reading that can't be found in the text gives no shape`() {
        assertNull(shape("Card XX4321 charged Rs.249.00 at ZEPTO", answer(merchant = "SWIGGY")))
        assertNull(shape("Card XX4321 charged Rs.249.00 at ZEPTO", answer(amount = "250")))
        assertNull(shape("Avl Bal Rs 249.00", answer(merchant = null)), "a balance is not the amount moved")
    }

    // ── Merge (Drain) ──

    private fun s(t: String) = MessageShape(t)

    @Test
    fun `shapes that differ in a little wording are one format`() {
        assertEquals(s("Card XX<ACCT> <ANY> Rs.<AMT> at <M> on <DATE>."), MessageShapes.merge(s("Card XX<ACCT> charged Rs.<AMT> at <M> on <DATE>."), s("Card XX<ACCT> billed Rs.<AMT> at <M> on <DATE>.")))
        assertEquals(s("a b c <AMT>"), MessageShapes.merge(s("a b c <AMT>"), s("a b c <AMT>")))
        assertEquals(s("<ANY> b c <AMT>"), MessageShapes.merge(s("<ANY> b c <AMT>"), s("z b c <AMT>")), "a wildcard takes any word")
    }

    @Test
    fun `different lengths, differing slots or too much difference are different formats`() {
        assertNull(MessageShapes.merge(s("Card charged Rs.<AMT>"), s("Card charged Rs.<AMT> today")))
        assertNull(MessageShapes.merge(s("Card charged Rs.<AMT> at <M>"), s("Card charged Rs.<AMT> at <N>")))
        assertNull(MessageShapes.merge(s("a b c <AMT>"), s("x y c <AMT>")), "half the words differ")
    }

    @Test
    fun `a readable template has the amount and few open-ended slots`() {
        assertTrue(TemplateReading.isReadable(s("Rs <AMT> at <M> on <DATE>")))
        assertFalse(TemplateReading.isReadable(s("Rs <AMT> <DATE> <DATE> <DATE> <M>")))
        assertTrue(TemplateReading.isReadable(s("Rs <AMT> to <M> by <M>")), "a repeated payee is one slot")
    }

    @Test
    fun `a template never prints more than its id and status`() {
        val t = MessageTemplate(userId = UUID.randomUUID(), channel = dev.adminos.api.domain.ingestion.fallback.MessageChannel.SMS, bankCode = null,
            direction = IngestDirection.DEBIT, shape = s("Rs <AMT>"), createdAt = Instant.EPOCH)
        assertTrue("Rs" !in t.toString())
        assertEquals("template:${t.id}", t.parsedBy)
        assertFailsWith<IllegalArgumentException> { t.copy(seen = 0) }
        assertEquals("Rs <AMT>", s("Rs <AMT>").toString())
    }
}
