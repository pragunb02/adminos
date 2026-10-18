package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.financial.BillRepository
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Net spending (spend positive, refunds negative) of one merchant on one local day. [recurring] when a known
 * recurring source took it: a mandate debit (`isRecurring`, AOS-502) or a bill's matched payment (AOS-508).
 */
data class SpendRow(val day: LocalDate, val merchant: String?, val recurring: Boolean, val amount: BigDecimal)

/** What the forecast reads about the past (AOS-714). Days are the user's own ([zone]). */
interface ForecastHistoryRepository {
    /** Spend and refund transactions (D-39) whose local day is in [range]; transfers, reversals and income are left out. */
    suspend fun spending(userId: UUID, range: DateRange, zone: ZoneId): List<SpendRow>

    /** Each income credit (D-39) whose local day is in [range]. */
    suspend fun incomes(userId: UUID, range: DateRange, zone: ZoneId): List<IncomeCredit>

    /** The local day of the user's first transaction of any kind; null when there is none. */
    suspend fun firstActivityDay(userId: UUID, zone: ZoneId): LocalDate?
}

/** The in-memory twin of the SQL implementation, over the in-memory repositories. */
class InMemoryForecastHistoryRepository(
    private val transactions: TransactionRepository,
    private val bills: BillRepository
) : ForecastHistoryRepository {

    override suspend fun spending(userId: UUID, range: DateRange, zone: ZoneId): List<SpendRow> {
        val paid = bills.findByUserId(userId, limit = Int.MAX_VALUE).mapNotNull { it.paymentTxnId }.toSet()
        return inRange(userId, range, zone)
            .filter { it.nature == TransactionNature.SPEND || it.nature == TransactionNature.REFUND }
            .groupBy { Triple(it.transactedAt.atZone(zone).toLocalDate(), it.merchantName, it.isRecurring || it.id in paid) }
            .map { (k, txns) ->
                SpendRow(k.first, k.second, k.third, txns.fold(BigDecimal.ZERO) { acc, t -> if (t.nature == TransactionNature.REFUND) acc - t.amount else acc + t.amount })
            }
    }

    override suspend fun incomes(userId: UUID, range: DateRange, zone: ZoneId): List<IncomeCredit> =
        inRange(userId, range, zone).filter { it.nature == TransactionNature.INCOME && it.amount.signum() > 0 }
            .map { IncomeCredit(it.transactedAt.atZone(zone).toLocalDate(), it.merchantName, it.amount) }

    override suspend fun firstActivityDay(userId: UUID, zone: ZoneId): LocalDate? =
        transactions.findByUserId(userId, limit = Int.MAX_VALUE).minOfOrNull { it.transactedAt }?.atZone(zone)?.toLocalDate()

    private suspend fun inRange(userId: UUID, range: DateRange, zone: ZoneId) = transactions.findByUserId(
        userId,
        from = range.from.atStartOfDay(zone).toInstant(),
        to = range.to.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1),
        limit = Int.MAX_VALUE
    )
}
