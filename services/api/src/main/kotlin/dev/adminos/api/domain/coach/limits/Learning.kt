package dev.adminos.api.domain.coach.limits

import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** When the owner's spending history starts (AOS-904): the first spend or refund. */
interface SpendHistory {
    suspend fun firstSpendAt(userId: UUID): Instant?
}

/** Over the transaction repository (dev mode and tests); Postgres asks `min()` directly. */
class RepositorySpendHistory(private val transactions: TransactionRepository) : SpendHistory {
    override suspend fun firstSpendAt(userId: UUID): Instant? =
        transactions.findByUserId(userId, limit = Int.MAX_VALUE)
            .filter { it.nature == TransactionNature.SPEND || it.nature == TransactionNature.REFUND }
            .minOfOrNull { it.transactedAt }
}

/**
 * The learning month (owner, 2026-10-17): the first [DAYS_NEEDED] days of data are for watching, not
 * limiting. Days of data = days from the first spend's local day to today, inclusive (0 with no data).
 */
data class LearningState(val daysOfData: Int) {
    init {
        require(daysOfData >= 0) { "days of data can't be negative" }
    }

    val ready: Boolean get() = daysOfData >= DAYS_NEEDED
    /** "Learning your habits: 12 of 30 days". */
    val label: String get() = "Learning your habits: ${minOf(daysOfData, DAYS_NEEDED)} of $DAYS_NEEDED days"

    companion object {
        const val DAYS_NEEDED = 30

        fun of(firstSpendAt: Instant?, today: LocalDate, zone: ZoneId): LearningState {
            val first = firstSpendAt?.atZone(zone)?.toLocalDate() ?: return LearningState(0)
            if (first.isAfter(today)) return LearningState(0)
            return LearningState((ChronoUnit.DAYS.between(first, today) + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }
}
