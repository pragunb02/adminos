package dev.adminos.api.domain.financial.splits

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic exports in Splitwise's format (AOS-911): header, one row per expense or payment, a total row. */
class SplitwiseCsvTest {

    private fun csv(vararg rows: String, header: String = "Date,Description,Category,Cost,Currency,Dev P.,Asha R.,Kabir M.") =
        ("\uFEFF" + (listOf(header) + rows).joinToString("\r\n") + "\r\n").toByteArray()

    private val export = csv(
        "2026-10-03,Dinner at Toit,Dining out,4000.00,INR,3000.00,-1500.00,-1500.00",
        "2026-10-04,Cab to airport,Taxi,900.00,INR,-300.00,600.00,-300.00",
        "2026-10-05,Payment,Payment,1500.00,INR,-1500.00,1500.00,0.00",
        "2026-10-06,\"Groceries, milk and \"\"bread\"\"\",Groceries,600.00,INR,0.00,300.00,-300.00",
        "2026-10-07,Kabir paid Dev,Payment,300.00,INR,300.00,0.00,-300.00",
        ",Total balance,,,INR,1500.00,900.00,-2400.00"
    )

    @Test
    fun `reads the owner's side of every expense and payment, and skips the total row`() {
        val r = SplitwiseCsv.parse(export, "Dev P.")
        assertEquals(3, r.members)
        assertEquals(0, r.unreadableRows)
        assertEquals(5, r.entries.size)
        val (dinner, cab, paidBack, groceries, paidOut) = r.entries
        assertEquals(LocalDate.of(2026, 10, 3), dinner.date)
        assertEquals(SplitEffect.ADJUST_DEBIT, dinner.effect)
        assertEquals(BigDecimal("1000.00"), dinner.ownerShare)
        assertEquals(BigDecimal("4000.00"), dinner.bankAmount)
        assertEquals(SplitEffect.OWED_SPEND, cab.effect)
        assertEquals(BigDecimal("300.00"), cab.ownerShare)
        assertEquals(SplitKind.PAYMENT, paidBack.kind)
        assertEquals(SplitEffect.SETTLE_IN, paidBack.effect)
        assertEquals("Asha R.", paidBack.counterparty)
        assertEquals(BigDecimal("1500.00"), paidBack.bankAmount)
        assertEquals("Groceries, milk and \"bread\"", groceries.description)
        assertEquals(SplitEffect.NONE, groceries.effect, "the owner wasn't in it")
        assertEquals(SplitEffect.SETTLE_OUT, paidOut.effect)
        assertEquals("Kabir M.", paidOut.counterparty)
    }

    @Test
    fun `the owner's column is found whatever its case, spacing or invisible characters`() {
        assertEquals(5, SplitwiseCsv.parse(export, "  dev p.  ").entries.size)
        assertEquals(5, SplitwiseCsv.parse(export, "Dev\u200B P.").entries.size)
        assertEquals(5, SplitwiseCsv.parse(export, "ＤＥＶ Ｐ.").entries.size, "full-width letters normalise (NFKC)")
    }

    @Test
    fun `an unknown, empty or doubled owner name is refused without naming anyone`() {
        val unknown = assertFailsWith<SplitImportException> { SplitwiseCsv.parse(export, "Someone Else") }
        assertEquals("SPLITWISE_ME_UNKNOWN", unknown.code)
        assertTrue("Asha" !in unknown.message && "Kabir" !in unknown.message)
        assertEquals("SPLITWISE_ME_REQUIRED", assertFailsWith<SplitImportException> { SplitwiseCsv.parse(export, " \u200B ") }.code)
        val twice = csv("2026-10-03,Tea,General,40.00,INR,20.00,-20.00,0.00", header = "Date,Description,Category,Cost,Currency,Dev P.,dev p.,Kabir M.")
        assertEquals("SPLITWISE_ME_AMBIGUOUS", assertFailsWith<SplitImportException> { SplitwiseCsv.parse(twice, "Dev P.") }.code)
    }

    @Test
    fun `rows that don't add up, or have a bad date or amount, are skipped and counted`() {
        val r = SplitwiseCsv.parse(csv(
            "2026-10-03,Unbalanced,General,100.00,INR,50.00,-10.00,0.00",
            "03/10/2026,Wrong date,General,100.00,INR,50.00,-50.00,0.00",
            "2026-02-30,No such day,General,100.00,INR,50.00,-50.00,0.00",
            "2026-10-03,Comma amount,General,\"1,000.00\",INR,500.00,-500.00,0.00",
            "2026-10-03,Three decimals,General,100.000,INR,50.00,-50.00,0.00",
            "2026-10-03,Zero cost,General,0.00,INR,0.00,0.00,0.00",
            "2026-10-03,Bad currency,General,100.00,rupees,50.00,-50.00,0.00",
            "2026-10-03,Owner over cost,General,100.00,INR,150.00,-150.00,0.00",
            "2026-10-03,Short row,General,100.00,INR,50.00",
            "2026-10-03,Formula,General,100.00,INR,=1+1,-50.00,0.00",
            "2026-10-03,Rounded thirds,General,100.00,INR,66.67,-33.33,-33.33",
            "",
            "2026-10-03 18:30:00,With a time,General,90.00,INR,-30.00,60.00,-30.00"
        ), "Dev P.")
        assertEquals(10, r.unreadableRows)
        assertEquals(listOf("Rounded thirds", "With a time"), r.entries.map { it.description }, "a paisa of rounding per member is fine")
    }

    @Test
    fun `text is kept as plain text, control characters dropped and long descriptions cut`() {
        val long = "x".repeat(500)
        val r = SplitwiseCsv.parse(csv(
            "2026-10-03,\"=HYPERLINK(\"\"http://evil.example\"\")\",General,100.00,INR,50.00,-50.00,0.00",
            "2026-10-03,<script>alert(1)</script>\u0007,General,100.00,INR,50.00,-50.00,0.00",
            "2026-10-03,$long,General,100.00,INR,50.00,-50.00,0.00"
        ), "Dev P.")
        assertEquals("=HYPERLINK(\"http://evil.example\")", r.entries[0].description, "stored as text, never evaluated")
        assertEquals("<script>alert(1)</script>", r.entries[1].description, "rendered as text by the web, never as HTML")
        assertEquals(SplitwiseCsv.MAX_DESCRIPTION_CHARS, r.entries[2].description.length)
    }

    @Test
    fun `the fingerprint is stable, tells identical rows apart by order, and ignores the split`() {
        val a = SplitwiseCsv.parse(csv("2026-10-03,Chai,General,40.00,INR,20.00,-20.00,0.00", "2026-10-03,Chai,General,40.00,INR,20.00,-20.00,0.00"), "Dev P.")
        val again = SplitwiseCsv.parse(csv("2026-10-03,Chai,General,40.00,INR,20.00,-20.00,0.00", "2026-10-03,Chai,General,40.00,INR,20.00,-20.00,0.00",
            "2026-10-04,New,General,40.00,INR,20.00,-20.00,0.00"), "Dev P.")
        assertNotEquals(a.entries[0].sourceRef, a.entries[1].sourceRef)
        assertEquals(a.entries.map { it.sourceRef }, again.entries.take(2).map { it.sourceRef })
        assertEquals(64, a.entries[0].sourceRef.length)
        val resplit = SplitwiseCsv.parse(csv("2026-10-03,Chai,General,40.00,INR,40.00,-40.00,0.00"), "Dev P.")
        assertEquals(a.entries[0].sourceRef, resplit.entries[0].sourceRef, "an edited split is the same row, reported as changed")
    }

    @Test
    fun `a settle-up names the other person only when it is clear who`() {
        val r = SplitwiseCsv.parse(csv(
            "2026-10-05,Payment,Payment,100.00,INR,-100.00,60.00,40.00",
            "2026-10-05,Payment,Payment,100.00,INR,-100.00,100.00,0.00",
            "2026-10-05,Asha paid Kabir,Payment,100.00,INR,0.00,100.00,-100.00"
        ), "Dev P.")
        assertNull(r.entries[0].counterparty)
        assertEquals("Asha R.", r.entries[1].counterparty)
        assertEquals(SplitEffect.NONE, r.entries[2].effect)
        assertNull(r.entries[2].counterparty)
    }

    @Test
    fun `files that aren't exports are refused with a reason`() {
        fun code(bytes: ByteArray, me: String = "Dev P.") = assertFailsWith<SplitImportException> { SplitwiseCsv.parse(bytes, me) }.code
        assertEquals("SPLITWISE_EMPTY", code(ByteArray(0)))
        assertEquals("SPLITWISE_NOT_AN_EXPORT", code(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0)))
        assertEquals("SPLITWISE_NOT_AN_EXPORT", code("Date,Amount,Narration\n2026-10-03,100,UPI".toByteArray()))
        assertEquals("SPLITWISE_NOT_AN_EXPORT", code(csv(header = "Date,Description,Category,Cost,Currency,Dev P.")))
        assertEquals("SPLITWISE_NOT_AN_EXPORT", code(csv(header = "Date,Description,Category,Cost,Currency,Dev P.,,Kabir M.")))
        assertEquals("SPLITWISE_NOT_AN_EXPORT", code(csv("2026-10-03,\"unclosed,General,1.00,INR,0,0,0")))
        assertEquals("SPLITWISE_NOT_AN_EXPORT", code(csv("2026-10-03,${"y".repeat(SplitwiseCsv.MAX_FIELD_CHARS + 1)},General,1.00,INR,0,0,0")))
        val wide = "Date,Description,Category,Cost,Currency," + (1..SplitwiseCsv.MAX_MEMBERS + 1).joinToString(",") { "M$it" }
        assertEquals("SPLITWISE_NOT_AN_EXPORT", code(csv(header = wide), "M1"))
        assertEquals("FILE_TOO_LARGE", code(ByteArray(SplitwiseCsv.MAX_BYTES + 1) { 'a'.code.toByte() }))
        val many = (0..SplitwiseCsv.MAX_ROWS + 2).map { "2026-10-03,Tea,General,2.00,INR,1.00,-1.00,0.00" }.toTypedArray()
        assertEquals("SPLITWISE_TOO_MANY_ROWS", code(csv(*many)))
    }

    @Test
    fun `a file without a final newline, or with LF only, reads the same`() {
        val lf = "Date,Description,Category,Cost,Currency,Dev P.,Asha R.\n2026-10-03,Tea,General,40.00,INR,20.00,-20.00"
        assertEquals(1, SplitwiseCsv.parse(lf.toByteArray(), "Dev P.").entries.size)
    }
}
