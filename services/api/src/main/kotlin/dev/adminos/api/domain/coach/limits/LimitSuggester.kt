package dev.adminos.api.domain.coach.limits

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.leaks.LeakFact
import dev.adminos.api.domain.coach.lenses.HabitLens
import dev.adminos.api.domain.coach.lenses.LensId
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** One week's spending in a lens, for a suggestion's working. */
data class WeekSpend(val week: DateRange, val spent: BigDecimal)

/**
 * A limit the coach proposes from the owner's own trend (owner, 2026-10-17). Never pushes: only an
 * accepted (stored) limit does. [weeks], [median], [stepPercent] and [roundTo] are its "how we got this".
 */
data class LimitSuggestion(
    val lens: HabitLens,
    val period: LimitPeriod,
    val amount: BigDecimal,
    val weeks: List<WeekSpend>,
    val median: BigDecimal,
    val stepPercent: Int,
    val roundTo: BigDecimal
)

/**
 * The suggestion rule (AOS-904): per lens, a weekly limit = the median of the last [WEEKS] complete
 * Monday–Sunday weeks, minus [STEP_PERCENT]%, rounded down to a multiple of [ROUND_TO], and at least [MIN_AMOUNT].
 * Only for a lens with spending in at least [MIN_ACTIVE_WEEKS] of those weeks, no limit yet, and no
 * "Not now" in the last [SNOOZE].
 */
object LimitSuggester {
    const val WEEKS = 4
    const val STEP_PERCENT = 10
    const val MIN_ACTIVE_WEEKS = 2
    val ROUND_TO = BigDecimal(50)
    val MIN_AMOUNT = BigDecimal(100)
    val SNOOZE: Duration = Duration.ofDays(30)

    /** The [WEEKS] complete weeks before the week containing [today], oldest first. */
    fun weeksBefore(today: LocalDate): List<DateRange> {
        val thisWeek = LimitPeriod.WEEKLY.rangeOf(today).from
        return (WEEKS downTo 1).map { n -> thisWeek.minusWeeks(n.toLong()).let { DateRange(it, it.plusDays(6)) } }
    }

    data class Input(
        val today: LocalDate,
        val now: Instant,
        val lenses: List<HabitLens>,
        /** Facts covering [weeksBefore]. */
        val facts: List<LeakFact>,
        val lensesWithLimits: Set<LensId>,
        val dismissed: Map<LensId, Instant>
    )

    fun suggest(input: Input): List<LimitSuggestion> {
        val weeks = weeksBefore(input.today)
        return input.lenses.mapNotNull { lens ->
            if (lens.id in input.lensesWithLimits) return@mapNotNull null
            if (input.dismissed[lens.id]?.let { Duration.between(it, input.now) < SNOOZE } == true) return@mapNotNull null
            val spends = weeks.map { w ->
                WeekSpend(w, input.facts.filter { lens.id in it.lenses && !it.local.toLocalDate().isBefore(w.from) && !it.local.toLocalDate().isAfter(w.to) }
                    .fold(BigDecimal.ZERO) { a, f -> a + f.signed })
            }
            if (spends.count { it.spent.signum() > 0 } < MIN_ACTIVE_WEEKS) return@mapNotNull null
            val median = median(spends.map { it.spent })
            val amount = roundDown(median.multiply(BigDecimal(100 - STEP_PERCENT)).divide(BigDecimal(100), 2, RoundingMode.DOWN))
            if (amount < MIN_AMOUNT) return@mapNotNull null
            LimitSuggestion(lens, LimitPeriod.WEEKLY, amount, spends, median, STEP_PERCENT, ROUND_TO)
        }
    }

    fun median(values: List<BigDecimal>): BigDecimal {
        require(values.isNotEmpty()) { "median of nothing" }
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid].setScale(2, RoundingMode.HALF_UP)
        else (sorted[mid - 1] + sorted[mid]).divide(BigDecimal(2), 2, RoundingMode.HALF_UP)
    }

    private fun roundDown(v: BigDecimal): BigDecimal = v.divide(ROUND_TO, 0, RoundingMode.DOWN).multiply(ROUND_TO).setScale(2)
}
