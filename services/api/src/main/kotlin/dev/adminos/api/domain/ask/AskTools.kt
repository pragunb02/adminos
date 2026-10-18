package dev.adminos.api.domain.ask

import dev.adminos.api.domain.ai.AiReasonCode
import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.Bucket
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.financial.TransactionCategory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * Who the tools run for, set by the server from the signed-in user's token: never by the model (D-67).
 * [today] is the user's local date, from the server clock in [zone].
 */
data class AskScope(val userId: UUID, val zone: ZoneId, val today: LocalDate)

data class CategoryLine(val category: TransactionCategory, val spent: BigDecimal, val payments: Int)

data class MerchantLine(val name: String, val spent: BigDecimal, val payments: Int, val category: TransactionCategory)

data class TrendPoint(val start: LocalDate, val spent: BigDecimal)

/** What one tool returned: the console's own numbers (spent = spend − refunds, transfers excluded; D-45, D-46). */
sealed interface AskToolResult {
    val call: AskToolCall
    val range: DateRange

    /** The single spent figure this result stands for, when it has one (for comparisons); null for a top-N list. */
    val total: BigDecimal?

    data class Summary(override val call: AskToolCall.SpendingSummary, override val range: DateRange, val spent: BigDecimal, val received: BigDecimal, val payments: Int) : AskToolResult {
        override val total: BigDecimal get() = spent
    }

    /** One row per category with money in the period; with a category filter, exactly that category's row (zero if none). */
    data class Categories(override val call: AskToolCall.CategorySpend, override val range: DateRange, val rows: List<CategoryLine>) : AskToolResult {
        override val total: BigDecimal get() = rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.spent }
    }

    /** The user's merchants matching the query; empty when none had spending in the period. */
    data class Merchant(override val call: AskToolCall.MerchantSpend, override val range: DateRange, val rows: List<MerchantLine>) : AskToolResult {
        override val total: BigDecimal get() = rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.spent }
    }

    data class TopMerchants(override val call: AskToolCall.TopMerchants, override val range: DateRange, val rows: List<MerchantLine>) : AskToolResult {
        override val total: BigDecimal? get() = null
    }

    data class Trend(override val call: AskToolCall.SpendingTrend, override val range: DateRange, val points: List<TrendPoint>) : AskToolResult {
        override val total: BigDecimal get() = points.fold(BigDecimal.ZERO) { acc, p -> acc + p.spent }
    }
}

/** Two results compared by the server, so a model never subtracts (D-67). [later] and [earlier] index the results. */
data class AskComparison(val later: Int, val earlier: Int, val laterSpent: BigDecimal, val earlierSpent: BigDecimal) {
    /** How much more (positive) or less (negative) the later period's spending was. */
    val difference: BigDecimal get() = laterSpent - earlierSpent

    /** The change as a percent of the earlier spending, one decimal; null when the earlier was not positive. */
    val changePct: BigDecimal? get() =
        earlierSpent.takeIf { it.signum() > 0 }?.let { difference.multiply(BigDecimal(100)).divide(it, 1, RoundingMode.HALF_UP) }

    companion object {
        /**
         * Every pair of results with a single total, of the same tool and filters, over different periods
         * ("food this month vs last month"), later period first.
         */
        fun of(results: List<AskToolResult>): List<AskComparison> {
            val out = mutableListOf<AskComparison>()
            for (i in results.indices) for (j in i + 1 until results.size) {
                val a = results[i]
                val b = results[j]
                val ta = a.total ?: continue
                val tb = b.total ?: continue
                if (filters(a.call) != filters(b.call) || a.range == b.range) continue
                out += if (a.range.from >= b.range.from) AskComparison(i, j, ta, tb) else AskComparison(j, i, tb, ta)
            }
            return out
        }

        /** The call without its period: two calls are comparable when this is equal. */
        private fun filters(call: AskToolCall): Any = when (call) {
            is AskToolCall.SpendingSummary -> call.copy(period = ANY)
            is AskToolCall.CategorySpend -> call.copy(period = ANY)
            is AskToolCall.MerchantSpend -> call.copy(period = ANY)
            is AskToolCall.TopMerchants -> call.copy(period = ANY)
            is AskToolCall.SpendingTrend -> call.copy(period = ANY)
        }

        private val ANY = PeriodSpec.of(PeriodPreset.TODAY)
    }
}

/** What running one call gave: a result, or a code saying why it couldn't run. */
sealed interface AskToolOutcome {
    data class Ran(val result: AskToolResult) : AskToolOutcome

    data class Refused(val code: AiReasonCode) : AskToolOutcome
}

/**
 * Runs [AskToolCall]s, read-only, over [AnalyticsService] for one [AskScope] (AOS-712/710). It adds no
 * definitions of its own: every figure is the console's (D-45, D-46), so an answer matches the Spending screen.
 */
class AskToolRunner(private val analytics: AnalyticsService) {
    companion object {
        /** A trend has at most this many points, so the answer prompt stays small. */
        const val MAX_POINTS = 62

        /** How many of the user's merchants a name is matched against. */
        const val MERCHANT_SCAN = 1_000

        val FUTURE_PERIOD = AiReasonCode("future_period")
        val PERIOD_TOO_LONG = AiReasonCode("period_too_long")
        val TOO_MANY_POINTS = AiReasonCode("too_many_points")
    }

    suspend fun run(scope: AskScope, call: AskToolCall): AskToolOutcome {
        val range = when (val r = PeriodResolver.resolve(call.period, scope.today)) {
            is ResolvedPeriod.Ok -> r.range
            ResolvedPeriod.Future -> return AskToolOutcome.Refused(FUTURE_PERIOD)
            ResolvedPeriod.TooLong -> return AskToolOutcome.Refused(PERIOD_TOO_LONG)
        }
        val (user, zone) = scope.userId to scope.zone
        val result = when (call) {
            is AskToolCall.SpendingSummary -> analytics.summary(user, range, zone).let {
                AskToolResult.Summary(call, range, it.current.spent, it.current.received, it.spendCount)
            }
            is AskToolCall.CategorySpend -> {
                val rows = analytics.categories(user, range, zone).map { CategoryLine(it.category, it.spent, it.count) }
                val chosen = if (call.category == null) rows else listOf(rows.firstOrNull { it.category == call.category } ?: CategoryLine(call.category, BigDecimal.ZERO, 0))
                AskToolResult.Categories(call, range, chosen)
            }
            is AskToolCall.MerchantSpend -> {
                val all = analytics.merchants(user, range, zone, MERCHANT_SCAN)
                val exact = all.filter { MerchantQuery.keyOf(it.merchant) == call.merchant.key }
                val rows = exact.ifEmpty { all.filter { contains(call.merchant, it.merchant) } }
                AskToolResult.Merchant(call, range, rows.map { MerchantLine(it.merchant, it.spent, it.count, it.category) })
            }
            is AskToolCall.TopMerchants -> {
                val rows = analytics.merchants(user, range, zone, call.limit, call.category)
                AskToolResult.TopMerchants(call, range, rows.map { MerchantLine(it.merchant, it.spent, it.count, it.category) })
            }
            is AskToolCall.SpendingTrend -> {
                if (points(range, call.bucket) > MAX_POINTS) return AskToolOutcome.Refused(TOO_MANY_POINTS)
                val points = analytics.series(user, range, call.bucket, zone, call.category)
                AskToolResult.Trend(call, range, points.map { TrendPoint(it.start, it.money.spent) })
            }
        }
        return AskToolOutcome.Ran(result)
    }

    /**
     * The query's words appear, in order and whole, in the merchant's name ("instamart" matches "Swiggy Instamart").
     * Used only when no merchant is named exactly the query, so "Swiggy" means Swiggy, not also Swiggy Instamart.
     */
    internal fun contains(query: MerchantQuery, merchant: String): Boolean =
        " ${MerchantQuery.keyOf(merchant)} ".contains(" ${query.key} ")

    private fun points(range: DateRange, bucket: Bucket): Long = when (bucket) {
        Bucket.DAY -> range.days
        Bucket.WEEK -> ChronoUnit.WEEKS.between(monday(range.from), monday(range.to)) + 1
        Bucket.MONTH -> ChronoUnit.MONTHS.between(YearMonth.from(range.from), YearMonth.from(range.to)) + 1
    }

    private fun monday(day: LocalDate) = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}
