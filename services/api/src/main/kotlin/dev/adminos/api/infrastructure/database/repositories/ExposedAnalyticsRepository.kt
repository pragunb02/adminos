package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.analytics.AnalyticsRepository
import dev.adminos.api.domain.analytics.SpendFact
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.infrastructure.database.dbQuery
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Postgres does the grouping (AOS-301): one row per (local day, nature, category, merchant).
 * The local day uses the user's zone, so "today" and month edges are theirs (AOS-120).
 * All values are bound parameters.
 */
class ExposedAnalyticsRepository : AnalyticsRepository {

    override suspend fun facts(userId: UUID, from: LocalDate, to: LocalDate, zone: ZoneId): List<SpendFact> = dbQuery {
        val start = from.atStartOfDay(zone).toInstant().toString()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toString()
        val text = TextColumnType()
        TransactionManager.current().exec(
            """
            SELECT (transacted_at AT TIME ZONE ?)::date AS day, nature::text AS nature, category::text AS category,
                   merchant_name, SUM(COALESCE(own_share, amount)) AS amount, COUNT(*) AS n
            FROM transactions
            WHERE user_id = ?::uuid AND transacted_at >= ?::timestamptz AND transacted_at < ?::timestamptz
              AND nature IN ('spend', 'refund', 'income')
            GROUP BY 1, 2, 3, 4
            """.trimIndent(),
            listOf(text to zone.id, text to userId.toString(), text to start, text to end)
        ) { rs ->
            val out = mutableListOf<SpendFact>()
            while (rs.next()) {
                out += SpendFact(
                    day = rs.getObject("day", LocalDate::class.java),
                    nature = TransactionNature.valueOf(rs.getString("nature").uppercase()),
                    category = TransactionCategory.valueOf(rs.getString("category").uppercase()),
                    merchant = rs.getString("merchant_name"),
                    amount = rs.getBigDecimal("amount"),
                    count = rs.getInt("n")
                )
            }
            out
        } ?: emptyList()
    }
}
