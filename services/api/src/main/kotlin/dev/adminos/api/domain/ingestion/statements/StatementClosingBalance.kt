package dev.adminos.api.domain.ingestion.statements

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A statement's closing balance and the day it was true (AOS-720). */
data class ClosingBalance(val amount: BigDecimal, val on: LocalDate, val observedAt: Instant) {
    override fun toString() = "ClosingBalance($on)" // never the amount
}

/** Why a statement gave no closing balance. */
enum class NoClosingBalance {
    /** No row has a balance (many card statements, some exports). */
    NONE,
    /** The rows' order can't be told (all one day, and the balances don't say). */
    ORDER_UNCLEAR,
    /**
     * The last balance doesn't follow from the one before it and the last row's amount: a misread column, or a
     * credit card statement whose "balance" is what is owed (it grows with spending).
     */
    UNRECONCILED
}

sealed interface ClosingBalanceResult {
    data class Found(val closing: ClosingBalance) : ClosingBalanceResult
    data class NotFound(val reason: NoClosingBalance) : ClosingBalanceResult
}

/**
 * The closing balance of a parsed statement (AOS-720, D-170): the running balance after its last row.
 * - Statements list rows oldest first or newest first; the dates say which, and when every row is on one day the
 *   running balances do.
 * - The closing figure must follow from the previous balance and the last row (previous − debit + credit), so a
 *   misread column or a card statement's amount owed is never taken for money in the account.
 * - It's true at the end of the closing day (or the statement period's end, if later), but never after the import:
 *   a statement downloaded today holds today's rows so far.
 */
object StatementClosingBalance {
    /** Statement dates are India dates (as the import uses). */
    private val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    private val END_OF_DAY: LocalTime = LocalTime.of(23, 59, 59)

    fun of(statement: ParsedStatement, importedAt: Instant): ClosingBalanceResult {
        val rows = statement.rows.filter { it.balance != null && (it.debit == null) != (it.credit == null) }
        if (rows.isEmpty()) return ClosingBalanceResult.NotFound(NoClosingBalance.NONE)
        val chronological = when {
            rows.last().date.isAfter(rows.first().date) -> rows
            rows.first().date.isAfter(rows.last().date) -> rows.reversed()
            rows.size == 1 -> rows
            reconciles(rows) -> rows
            reconciles(rows.reversed()) -> rows.reversed()
            else -> return ClosingBalanceResult.NotFound(NoClosingBalance.ORDER_UNCLEAR)
        }
        val last = chronological.last()
        val previous = chronological.getOrNull(chronological.size - 2)
        if (previous != null && !follows(previous, last)) return ClosingBalanceResult.NotFound(NoClosingBalance.UNRECONCILED)
        val importDay = importedAt.atZone(ZONE).toLocalDate()
        val on = statement.periodEnd?.takeIf { it.isAfter(last.date) && !it.isAfter(importDay) } ?: last.date
        val endOfDay = on.atTime(END_OF_DAY).atZone(ZONE).toInstant()
        val balance = last.balance ?: return ClosingBalanceResult.NotFound(NoClosingBalance.NONE)
        return ClosingBalanceResult.Found(ClosingBalance(balance.setScale(2), on, minOf(endOfDay, importedAt)))
    }

    /** Every adjacent pair follows (previous − debit + credit = next). */
    private fun reconciles(rows: List<StatementRow>): Boolean = rows.zipWithNext().all { (a, b) -> follows(a, b) }

    private fun follows(previous: StatementRow, next: StatementRow): Boolean {
        val before = previous.balance ?: return false
        val after = next.balance ?: return false
        return (before - (next.debit ?: BigDecimal.ZERO) + (next.credit ?: BigDecimal.ZERO)).compareTo(after) == 0
    }
}
