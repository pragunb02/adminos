package dev.adminos.api.domain.ingestion.statements

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** AOS-720: a statement's closing balance is the running balance after its last row, and only when it reconciles. */
class StatementClosingBalanceTest {
    /** Imported on 20 Oct 2026, 15:00 in India. */
    private val imported = Instant.parse("2026-10-20T09:30:00Z")

    private fun row(day: Int, debit: String? = null, credit: String? = null, bal: String?) =
        StatementRow(LocalDate.of(2026, 10, day), "ROW", debit?.let(::BigDecimal), credit?.let(::BigDecimal), bal?.let(::BigDecimal), null)

    private fun statement(rows: List<StatementRow>, periodEnd: LocalDate? = rows.maxOfOrNull { it.date }) =
        ParsedStatement("hdfc", "1234", rows.minOfOrNull { it.date }, periodEnd, rows, 0, emptyList())

    private fun found(s: ParsedStatement) = assertIs<ClosingBalanceResult.Found>(StatementClosingBalance.of(s, imported)).closing
    private fun reason(s: ParsedStatement) = assertIs<ClosingBalanceResult.NotFound>(StatementClosingBalance.of(s, imported)).reason

    @Test
    fun `oldest-first statements close on their last row, at the end of that day in India`() {
        val c = found(statement(listOf(row(1, credit = "1000.00", bal = "1000.00"), row(2, debit = "100.00", bal = "900.00"), row(2, debit = "50.00", bal = "850.00"))))
        assertEquals(BigDecimal("850.00"), c.amount)
        assertEquals(LocalDate.of(2026, 10, 2), c.on)
        assertEquals(Instant.parse("2026-10-02T18:29:59Z"), c.observedAt)
    }

    @Test
    fun `newest-first statements close on their first row`() {
        val c = found(statement(listOf(row(3, debit = "50.00", bal = "850.00"), row(2, debit = "100.00", bal = "900.00"), row(1, credit = "1000.00", bal = "1000.00"))))
        assertEquals(BigDecimal("850.00"), c.amount)
        assertEquals(LocalDate.of(2026, 10, 3), c.on)
    }

    @Test
    fun `rows all on one day are ordered by their balances, and unclear orders are refused`() {
        val asc = listOf(row(5, credit = "100.00", bal = "1100.00"), row(5, debit = "300.00", bal = "800.00"))
        assertEquals(BigDecimal("800.00"), found(statement(asc)).amount)
        assertEquals(BigDecimal("800.00"), found(statement(asc.reversed())).amount)
        assertEquals(NoClosingBalance.ORDER_UNCLEAR, reason(statement(listOf(row(5, debit = "1.00", bal = "10.00"), row(5, debit = "1.00", bal = "50.00")))))
    }

    @Test
    fun `a closing that doesn't follow from the row before is refused, like a card's amount owed`() {
        assertEquals(NoClosingBalance.UNRECONCILED, reason(statement(listOf(row(1, debit = "100.00", bal = "100.00"), row(2, debit = "50.00", bal = "150.00")))))
    }

    @Test
    fun `no balances means no closing, and rows without one are skipped`() {
        assertEquals(NoClosingBalance.NONE, reason(statement(listOf(row(1, debit = "1.00", bal = null)))))
        assertEquals(NoClosingBalance.NONE, reason(statement(emptyList())))
        assertEquals(BigDecimal("900.00"), found(statement(listOf(row(1, credit = "1000.00", bal = "1000.00"), row(2, debit = "100.00", bal = "900.00"), row(3, debit = "5.00", bal = null)))).amount)
        assertEquals(NoClosingBalance.UNCHECKABLE, reason(statement(listOf(row(1, debit = "1.00", bal = "42.00")))), "one balance can't be checked, so it isn't taken")
    }

    @Test
    fun `the period's end dates the closing when later, but never after the import`() {
        val rows = listOf(row(1, credit = "10.00", bal = "10.00"), row(1, debit = "4.00", bal = "6.00"))
        assertEquals(LocalDate.of(2026, 10, 15), found(statement(rows, LocalDate.of(2026, 10, 15))).on)
        assertEquals(LocalDate.of(2026, 10, 1), found(statement(rows, LocalDate.of(2026, 10, 25))).on, "a period ending after today isn't over")
        val today = found(statement(listOf(row(20, credit = "10.00", bal = "10.00"), row(20, debit = "1.00", bal = "9.00"))))
        assertEquals(imported, today.observedAt, "a statement downloaded today is true as of the import")
    }

    @Test
    fun `printing a closing never shows the figure`() {
        assertEquals("ClosingBalance(2026-10-01)", found(statement(listOf(row(1, credit = "4322.00", bal = "4322.00"), row(1, debit = "1.00", bal = "4321.00")))).toString())
    }
}
