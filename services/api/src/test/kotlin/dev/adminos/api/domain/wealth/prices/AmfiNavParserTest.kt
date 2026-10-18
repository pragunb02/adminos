package dev.adminos.api.domain.wealth.prices

import dev.adminos.api.domain.wealth.UnitPrice
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** AOS-810: AMFI's NAVAll.txt read defensively. Synthetic rows (invented codes and `INFZZ9…` ISINs) in the real layout. */
class AmfiNavParserTest {

    companion object {
        const val HEADER = "Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Plan;Option;Net Asset Value;Date"

        /** A small file in the published layout: headings, blank lines, CRLF, a BOM. */
        fun file(vararg rows: String) = "${Char(0xFEFF)}$HEADER\r\n \r\nOpen Ended Schemes(Equity Scheme - Flexi Cap Fund)\r\n \r\nExample Mutual Fund\r\n \r\n" +
            rows.joinToString("\r\n") + "\r\n \r\n"

        fun row(code: String, growth: String, reinvest: String, nav: String, date: String = "16-Oct-2026", name: String = "Example Flexi Cap Fund") =
            "$code;$growth;$reinvest;$name;Direct Plan;Growth Option;$nav;$date"
    }

    @Test
    fun `rows are read with both ISINs, and headings and blank lines are skipped`() {
        val f = AmfiNavParser.parse(file(
            row("900001", "INFZZ9000010", "-", "120.1234"),
            row("900002", "INFZZ9000028", "INFZZ9000036", "25.98765432"),
        ))
        assertEquals(0, f.skipped)
        assertEquals(listOf("900001", "900002"), f.navs.map { it.schemeCode })
        assertEquals(listOf("INFZZ9000010"), f.navs[0].isins)
        assertEquals(listOf("INFZZ9000028", "INFZZ9000036"), f.navs[1].isins)
        assertEquals(UnitPrice.parse("25.98765432"), f.navs[1].nav)
        assertEquals(LocalDate.of(2026, 10, 16), f.navs[0].date)
    }

    @Test
    fun `zero, missing or malformed NAVs and dates are skipped and counted, never stored as zero`() {
        val f = AmfiNavParser.parse(file(
            row("900001", "INFZZ9000010", "-", "0.0000"),
            row("900002", "INFZZ9000028", "-", "N.A."),
            row("900003", "INFZZ9000036", "-", "-1.5"),
            row("900004", "INFZZ9000044", "-", "12.5", date = "2026-10-16"),
            row("90000X", "INFZZ9000051", "-", "12.5"),
            "900006;INFZZ9000069;-;Too;Few;Columns;1.0",
            row("900007", "not-an-isin", "-", "10.5"),
        ))
        assertEquals(6, f.skipped)
        assertEquals(listOf("900007"), f.navs.map { it.schemeCode })
        assertTrue(f.navs.single().isins.isEmpty(), "a malformed ISIN is dropped, the scheme code still prices it")
    }

    @Test
    fun `a changed header stops the import instead of mis-reading columns`() {
        val old = "Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Net Asset Value;Date"
        assertFailsWith<AmfiFormatException> { AmfiNavParser.parse("$old\n100;INFZZ9000010;-;X;1.0;16-Oct-2026") }
        assertFailsWith<AmfiFormatException> { AmfiNavParser.parse("") }
        assertFailsWith<AmfiFormatException> { AmfiNavParser.parse("<html>Service unavailable</html>") }
    }
}
