package dev.adminos.api.domain.wealth.prices

import dev.adminos.api.domain.wealth.Instrument
import dev.adminos.api.domain.wealth.UnitPrice
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** One scheme's NAV from AMFI's daily file. [isins] are the growth/payout and reinvestment ISINs (either may be absent). */
data class AmfiNav(val schemeCode: String, val isins: List<String>, val nav: UnitPrice, val date: LocalDate)

data class AmfiNavFile(val navs: List<AmfiNav>, val skipped: Int)

/** The file isn't in the format we know: stop rather than mis-read columns (WEALTH-RESEARCH §2.1). */
class AmfiFormatException(message: String) : RuntimeException(message)

/**
 * Reads AMFI's `NAVAll.txt` (AOS-810), checked 2026-10-18:
 * `Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Plan;Option;Net Asset Value;Date`,
 * with blank lines and category / fund-house heading lines between the rows.
 *
 * Defensive by design: the header must have the 8 known columns (the Plan/Option columns were added recently, so a
 * column count change means the format changed and we stop); rows with a missing, zero or non-numeric NAV, a bad
 * date or a bad scheme code are skipped and counted, never stored as zero.
 */
object AmfiNavParser {
    const val COLUMNS = 8
    private const val MAX_ROWS = 200_000
    private val DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)
    private val NAV = Regex("\\d{1,12}(\\.\\d{1,10})?")

    fun parse(text: String): AmfiNavFile {
        val lines = text.removePrefix(Char(0xFEFF).toString()).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.iterator()
        if (!lines.hasNext()) throw AmfiFormatException("The AMFI NAV file is empty")
        checkHeader(lines.next().split(';').map { it.trim() })
        val navs = mutableListOf<AmfiNav>()
        var skipped = 0
        var rows = 0
        for (line in lines) {
            val cols = line.split(';')
            if (cols.size == 1) continue // a category or fund-house heading
            if (++rows > MAX_ROWS) throw AmfiFormatException("The AMFI NAV file has more rows than expected")
            val nav = if (cols.size == COLUMNS) row(cols.map { it.trim() }) else null
            if (nav == null) skipped++ else navs += nav
        }
        return AmfiNavFile(navs, skipped)
    }

    private fun checkHeader(header: List<String>) {
        val ok = header.size == COLUMNS && header[0] == "Scheme Code" && header[1].startsWith("ISIN") && header[2].startsWith("ISIN") &&
            header[3] == "Scheme Name" && header[6] == "Net Asset Value" && header[7] == "Date"
        if (!ok) throw AmfiFormatException("The AMFI NAV file format changed: expected $COLUMNS columns ending in Net Asset Value;Date, got ${header.size}")
    }

    private fun row(c: List<String>): AmfiNav? {
        val code = c[0].takeIf { it.matches(Instrument.AMFI_CODE) } ?: return null
        val raw = c[6].takeIf { it.matches(NAV) } ?: return null
        val nav = java.math.BigDecimal(raw).takeIf { it.signum() > 0 }?.let(UnitPrice::of) ?: return null
        val date = try { LocalDate.parse(c[7], DATE) } catch (e: DateTimeParseException) { return null }
        val isins = listOf(c[1], c[2]).filter { it.matches(Instrument.ISIN) }
        return AmfiNav(code, isins, nav, date)
    }
}
