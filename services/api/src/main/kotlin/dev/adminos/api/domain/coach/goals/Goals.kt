package dev.adminos.api.domain.coach.goals

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.leaks.LeakFact
import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.coach.limits.LimitPeriod
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** "Cut <lens> by X% vs the last 3 months" or "keep <lens> under ₹Y" (AOS-907). */
enum class GoalKind { CUT_PERCENT, CAP_AMOUNT }

/**
 * One goal on a habit lens. [target] is per [period] and fixed when the goal is set: a cut goal freezes its
 * baseline (the last 3 full months), so progress isn't measured against a moving average.
 */
data class Goal(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val lens: LensId,
    val kind: GoalKind,
    /** A percent (1..90) for a cut, rupees for a cap. */
    val value: BigDecimal,
    val period: LimitPeriod,
    /** Spend per period over the baseline months (cut goals), else null. */
    val baseline: BigDecimal?,
    val target: BigDecimal,
    /** The local day the goal was set. */
    val startedOn: LocalDate,
    val createdAt: Instant
) {
    init {
        when (kind) {
            GoalKind.CUT_PERCENT -> {
                require(value.stripTrailingZeros().scale() <= 0 && value >= BigDecimal.ONE && value <= BigDecimal(90)) { "value: a cut is 1..90 percent" }
                require(baseline != null && baseline.signum() > 0) { "a cut needs spending in the last 3 months to cut from" }
            }
            GoalKind.CAP_AMOUNT -> require(value.signum() > 0 && value.scale() <= 2 && value <= BigDecimal("10000000")) { "value: an amount above 0" }
        }
        require(target.signum() >= 0) { "target can't be negative" }
    }

    companion object {
        /** The cut goal's baseline: the 3 full calendar months before [today]. */
        fun baselineMonths(today: LocalDate): DateRange {
            val last = YearMonth.from(today).minusMonths(1)
            return DateRange(last.minusMonths(2).atDay(1), last.atEndOfMonth())
        }

        /** Spend per [period] over [months]: a week is 7 of the months' days; a month is a third of the three. */
        fun perPeriod(total: BigDecimal, months: DateRange, period: LimitPeriod): BigDecimal = when (period) {
            LimitPeriod.MONTHLY -> total.divide(BigDecimal(3), 2, RoundingMode.HALF_UP)
            LimitPeriod.WEEKLY -> total.multiply(BigDecimal(7)).divide(BigDecimal(months.days), 2, RoundingMode.HALF_UP)
        }

        fun targetOf(kind: GoalKind, value: BigDecimal, baseline: BigDecimal?): BigDecimal = when (kind) {
            GoalKind.CAP_AMOUNT -> value
            GoalKind.CUT_PERCENT -> (baseline ?: BigDecimal.ZERO).multiply(BigDecimal(100) - value).divide(BigDecimal(100), 0, RoundingMode.DOWN).setScale(2)
        }
    }
}

/** One period of a goal: spent against the target. The current period is "so far". */
data class GoalPeriod(val range: DateRange, val spent: BigDecimal, val target: BigDecimal, val current: Boolean) {
    val met: Boolean get() = spent <= target
}

/** No-spend days in the lens: the run ending today (0 if there was an order today) and the best run in the last year of data. Refunds never break a run. */
data class Streak(val current: Int, val best: Int)

data class GoalProgress(val goal: Goal, val label: String, val periods: List<GoalPeriod>, val streak: Streak) {
    val currentPeriod: GoalPeriod get() = periods.last()
    /** Completed periods that met the target. */
    val metCount: Int get() = periods.count { !it.current && it.met }
    val completedCount: Int get() = periods.count { !it.current }
}

/** Pure goal arithmetic (AOS-907). */
object GoalMath {
    /** Up to [MAX_PERIODS] periods from the goal's start to the one containing [today], oldest first. */
    fun periods(goal: Goal, today: LocalDate, facts: List<LeakFact>): List<GoalPeriod> {
        val first = goal.period.rangeOf(goal.startedOn).from
        val ranges = generateSequence(goal.period.rangeOf(today)) { r -> goal.period.rangeOf(r.from.minusDays(1)) }
            .takeWhile { !it.to.isBefore(first) }.take(MAX_PERIODS).toList().reversed()
        return ranges.map { r ->
            val upTo = minOf(r.to, today)
            GoalPeriod(r, spent(goal.lens, facts, r.from, upTo), goal.target, current = !today.isAfter(r.to) && !today.isBefore(r.from))
        }
    }

    fun streak(lens: LensId, facts: List<LeakFact>, since: LocalDate, today: LocalDate): Streak {
        val spendDays = facts.filter { lens in it.lenses && it.isOrder }.map { it.local.toLocalDate() }.toSet()
        var current = 0
        var d = today
        while (!d.isBefore(since) && d !in spendDays) { current++; d = d.minusDays(1) }
        var best = 0
        var run = 0
        var day = since
        while (!day.isAfter(today)) {
            if (day in spendDays) run = 0 else { run++; best = maxOf(best, run) }
            day = day.plusDays(1)
        }
        return Streak(current, best)
    }

    private fun spent(lens: LensId, facts: List<LeakFact>, from: LocalDate, to: LocalDate): BigDecimal =
        facts.filter { lens in it.lenses && !it.local.toLocalDate().isBefore(from) && !it.local.toLocalDate().isAfter(to) }
            .fold(BigDecimal.ZERO) { a, f -> a + f.signed }

    /** At most this many periods are shown (and computed). */
    const val MAX_PERIODS = 12
    /** Streaks look back at most this far. */
    const val MAX_STREAK_DAYS = 366L

    /** How far back streaks look: a year, but never before the first day with data (days before it aren't "no spend"). */
    fun lookback(today: LocalDate): LocalDate = today.minus(MAX_STREAK_DAYS - 1, ChronoUnit.DAYS)
}

class DuplicateGoalException(message: String) : RuntimeException(message)

interface GoalRepository {
    /** @throws DuplicateGoalException when the user already has a goal on this lens */
    suspend fun save(goal: Goal): Goal
    suspend fun findByUser(userId: UUID): List<Goal>
    /** @return whether there was one of this user's */
    suspend fun delete(userId: UUID, id: UUID): Boolean
}

class InMemoryGoalRepository : GoalRepository {
    private val store = ConcurrentHashMap<UUID, Goal>()

    @Synchronized
    private fun put(goal: Goal): Goal {
        if (store.values.any { it.userId == goal.userId && it.lens == goal.lens }) throw DuplicateGoalException("There is already a goal for this habit")
        store[goal.id] = goal
        return goal
    }

    override suspend fun save(goal: Goal): Goal = put(goal)
    override suspend fun findByUser(userId: UUID) = store.values.filter { it.userId == userId }.sortedWith(compareBy({ it.createdAt }, { it.lens.value }))
    override suspend fun delete(userId: UUID, id: UUID): Boolean = store[id]?.takeIf { it.userId == userId }?.let { store.remove(id) != null } ?: false
}
