package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.forecast.ForecastHistoryRepository
import dev.adminos.api.domain.forecast.IncomeCredit
import dev.adminos.api.domain.forecast.SpendRow
import dev.adminos.api.infrastructure.database.dbQuery
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Postgres groups the forecast's history (AOS-714): net spending per (local day, merchant, recurring),
 * where recurring means a mandate debit or a bill's matched payment. Local days use the user's zone.
 * All values are bound parameters.
 */
class ExposedForecastHistoryRepository : ForecastHistoryRepository {
    private val text = TextColumnType()

    override suspend fun spending(userId: UUID, range: DateRange, zone: ZoneId): List<SpendRow> = dbQuery {
        TransactionManager.current().exec(
            """
            SELECT (t.transacted_at AT TIME ZONE ?)::date AS day, t.merchant_name,
                   (t.is_recurring OR EXISTS (SELECT 1 FROM bills b WHERE b.payment_txn_id = t.id)) AS recurring,
                   SUM(CASE WHEN t.nature = 'refund' THEN -t.amount ELSE t.amount END) AS amount
            FROM transactions t
            WHERE t.user_id = ?::uuid AND t.transacted_at >= ?::timestamptz AND t.transacted_at < ?::timestamptz
              AND t.nature IN ('spend', 'refund')
            GROUP BY 1, 2, 3
            """.trimIndent(),
            args(userId, range, zone)
        ) { rs ->
            val out = mutableListOf<SpendRow>()
            while (rs.next()) {
                out += SpendRow(rs.getObject("day", LocalDate::class.java), rs.getString("merchant_name"), rs.getBoolean("recurring"), rs.getBigDecimal("amount"))
            }
            out
        } ?: emptyList()
    }

    override suspend fun incomes(userId: UUID, range: DateRange, zone: ZoneId): List<IncomeCredit> = dbQuery {
        TransactionManager.current().exec(
            """
            SELECT (transacted_at AT TIME ZONE ?)::date AS day, merchant_name, amount
            FROM transactions
            WHERE user_id = ?::uuid AND transacted_at >= ?::timestamptz AND transacted_at < ?::timestamptz
              AND nature = 'income' AND amount > 0
            """.trimIndent(),
            args(userId, range, zone)
        ) { rs ->
            val out = mutableListOf<IncomeCredit>()
            while (rs.next()) out += IncomeCredit(rs.getObject("day", LocalDate::class.java), rs.getString("merchant_name"), rs.getBigDecimal("amount"))
            out
        } ?: emptyList()
    }

    override suspend fun firstActivityDay(userId: UUID, zone: ZoneId): LocalDate? = dbQuery {
        TransactionManager.current().exec(
            "SELECT MIN(transacted_at) AS first FROM transactions WHERE user_id = ?::uuid",
            listOf(text to userId.toString())
        ) { rs -> if (rs.next()) rs.getObject("first", OffsetDateTime::class.java)?.atZoneSameInstant(zone)?.toLocalDate() else null }
    }

    private fun args(userId: UUID, range: DateRange, zone: ZoneId) = listOf(
        text to zone.id,
        text to userId.toString(),
        text to range.from.atStartOfDay(zone).toInstant().toString(),
        text to range.to.plusDays(1).atStartOfDay(zone).toInstant().toString()
    )
}
