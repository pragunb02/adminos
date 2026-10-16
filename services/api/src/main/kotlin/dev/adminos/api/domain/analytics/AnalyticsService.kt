package dev.adminos.api.domain.analytics

import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

enum class Bucket { DAY, WEEK, MONTH }

data class DateRange(val from: LocalDate, val to: LocalDate) {
    val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1
    /** The same number of days immediately before. */
    fun previous(): DateRange = DateRange(from.minusDays(days), from.minusDays(1))
}

data class Money(val spent: BigDecimal, val received: BigDecimal) {
    val net: BigDecimal get() = received - spent
}

data class Summary(val range: DateRange, val current: Money, val spendCount: Int, val previousRange: DateRange, val previous: Money) {
    /** Percent change in spent vs the previous period; null when there was nothing to compare with. */
    val spentChangePct: BigDecimal? get() =
        previous.spent.takeIf { it.signum() > 0 }?.let { (current.spent - it).multiply(BigDecimal(100)).divide(it, 1, RoundingMode.HALF_UP) }
}

data class SeriesPoint(val start: LocalDate, val money: Money)
data class CategoryShare(val category: TransactionCategory, val spent: BigDecimal, val share: Double, val count: Int, val previousSpent: BigDecimal)
data class MerchantSpend(val merchant: String, val spent: BigDecimal, val count: Int, val category: TransactionCategory)

/**
 * The console's numbers (AOS-301, D-45, D-46). For any period:
 * - **spent** = spend − refunds (a refund reduces what it refunds; D-39/D-40)
 * - **received** = income; transfers and reversals count as neither
 * - nothing is clipped, so total = Σ categories = Σ series buckets for the same period.
 */
class AnalyticsService(private val repository: AnalyticsRepository, private val clock: Clock = Clock.systemUTC()) {

    companion object {
        const val MAX_DAYS = 3 * 366L
        const val MAX_BUCKETS = 400
        const val UNKNOWN_MERCHANT = "Unknown"
    }

    /** This month so far, in the user's zone. */
    fun defaultRange(zone: ZoneId): DateRange {
        val today = LocalDate.now(clock.withZone(zone))
        return DateRange(today.withDayOfMonth(1), today)
    }

    fun validate(range: DateRange) {
        require(!range.to.isBefore(range.from)) { "to: must be on or after from" }
        require(range.days <= MAX_DAYS) { "from: a range can cover at most $MAX_DAYS days" }
    }

    suspend fun summary(userId: UUID, range: DateRange, zone: ZoneId): Summary {
        validate(range)
        val prev = range.previous()
        val facts = repository.facts(userId, prev.from, range.to, zone)
        val (now, before) = facts.partition { !it.day.isBefore(range.from) }
        return Summary(range, money(now), now.filter { it.nature == TransactionNature.SPEND }.sumOf { it.count }, prev, money(before))
    }

    suspend fun series(userId: UUID, range: DateRange, bucket: Bucket, zone: ZoneId): List<SeriesPoint> {
        validate(range)
        val starts = generateSequence(bucketStart(range.from, bucket)) { next(it, bucket) }.takeWhile { !it.isAfter(range.to) }.toList()
        require(starts.size <= MAX_BUCKETS) { "bucket: ${starts.size} ${bucket.name.lowercase()}s is too many; use a larger bucket or a shorter range" }
        val byBucket = repository.facts(userId, range.from, range.to, zone).groupBy { bucketStart(it.day, bucket) }
        return starts.map { SeriesPoint(it, money(byBucket[it].orEmpty())) }
    }

    suspend fun categories(userId: UUID, range: DateRange, zone: ZoneId): List<CategoryShare> {
        validate(range)
        val prev = range.previous()
        val facts = repository.facts(userId, prev.from, range.to, zone).filter { it.nature != TransactionNature.INCOME }
        val (now, before) = facts.partition { !it.day.isBefore(range.from) }
        val current = now.groupBy { it.category }
        val previous = before.groupBy { it.category }.mapValues { spent(it.value) }
        val total = current.values.map { spent(it) }.filter { it.signum() > 0 }.fold(BigDecimal.ZERO, BigDecimal::add)
        return current.map { (category, f) ->
            val s = spent(f)
            CategoryShare(category, s, share(s, total), f.filter { it.nature == TransactionNature.SPEND }.sumOf { it.count },
                previous[category] ?: BigDecimal.ZERO)
        }.sortedByDescending { it.spent }
    }

    suspend fun merchants(userId: UUID, range: DateRange, zone: ZoneId, limit: Int): List<MerchantSpend> {
        validate(range)
        return repository.facts(userId, range.from, range.to, zone)
            .filter { it.nature != TransactionNature.INCOME }
            .groupBy { it.merchant?.takeIf(String::isNotBlank) ?: UNKNOWN_MERCHANT }
            .map { (merchant, f) ->
                val spends = f.filter { it.nature == TransactionNature.SPEND }
                val category = spends.groupBy { it.category }.maxByOrNull { (_, v) -> spent(v) }?.key ?: f.first().category
                MerchantSpend(merchant, spent(f), spends.sumOf { it.count }, category)
            }
            .filter { it.spent.signum() > 0 }
            .sortedByDescending { it.spent }
            .take(limit)
    }

    private fun money(facts: List<SpendFact>) = Money(spent(facts), sum(facts, TransactionNature.INCOME))

    private fun spent(facts: List<SpendFact>) = sum(facts, TransactionNature.SPEND) - sum(facts, TransactionNature.REFUND)

    private fun sum(facts: List<SpendFact>, nature: TransactionNature) =
        facts.filter { it.nature == nature }.fold(BigDecimal.ZERO) { acc, f -> acc + f.amount }

    private fun share(part: BigDecimal, total: BigDecimal): Double =
        if (total.signum() <= 0 || part.signum() <= 0) 0.0 else part.divide(total, 4, RoundingMode.HALF_UP).toDouble()

    private fun bucketStart(day: LocalDate, bucket: Bucket): LocalDate = when (bucket) {
        Bucket.DAY -> day
        Bucket.WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        Bucket.MONTH -> day.withDayOfMonth(1)
    }

    private fun next(start: LocalDate, bucket: Bucket): LocalDate = when (bucket) {
        Bucket.DAY -> start.plusDays(1)
        Bucket.WEEK -> start.plusWeeks(1)
        Bucket.MONTH -> start.plusMonths(1)
    }
}
