package dev.adminos.api.domain.analytics

import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Money aggregated by local day, nature, category and merchant (AOS-301). The console's
 * numbers are all computed from these by [AnalyticsService], so there is one definition.
 */
data class SpendFact(
    val day: LocalDate,
    val nature: TransactionNature,
    val category: TransactionCategory,
    val merchant: String?,
    val amount: BigDecimal,
    val count: Int
)

interface AnalyticsRepository {
    /**
     * Facts for spend, refund and income transactions of [userId] whose local day (in [zone]) is
     * within [from]..[to]. Transfers and reversals are excluded (D-39).
     */
    suspend fun facts(userId: UUID, from: LocalDate, to: LocalDate, zone: ZoneId): List<SpendFact>
}

/** Groups the in-memory transactions the same way the SQL implementation does. */
class InMemoryAnalyticsRepository(private val transactions: TransactionRepository) : AnalyticsRepository {
    override suspend fun facts(userId: UUID, from: LocalDate, to: LocalDate, zone: ZoneId): List<SpendFact> {
        val start: Instant = from.atStartOfDay(zone).toInstant()
        val end: Instant = to.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1)
        return transactions.findByUserId(userId, from = start, to = end, limit = Int.MAX_VALUE)
            .filter { it.nature in COUNTED }
            .groupBy { listOf(it.transactedAt.atZone(zone).toLocalDate(), it.nature, it.category, it.merchantName) }
            .map { (k, txns) ->
                SpendFact(k[0] as LocalDate, k[1] as TransactionNature, k[2] as TransactionCategory, k[3] as String?,
                    txns.fold(BigDecimal.ZERO) { acc, t -> acc + t.amount }, txns.size)
            }
    }

    companion object {
        val COUNTED = setOf(TransactionNature.SPEND, TransactionNature.REFUND, TransactionNature.INCOME)
    }
}
