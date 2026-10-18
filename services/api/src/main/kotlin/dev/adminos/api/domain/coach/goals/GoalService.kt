package dev.adminos.api.domain.coach.goals

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.leaks.LeakService
import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.coach.limits.LimitPeriod
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class GoalRequest(val lens: String, val kind: GoalKind, val value: BigDecimal, val period: LimitPeriod)

sealed interface GoalOutcome {
    data class Saved(val progress: GoalProgress) : GoalOutcome
    data class UnknownLens(val id: String) : GoalOutcome
    /** A cut needs spending in the baseline months to cut from. */
    data class NoBaseline(val months: DateRange) : GoalOutcome
    data class Duplicate(val message: String) : GoalOutcome
}

/** Goals on habit lenses (AOS-907): set, list with weekly (or monthly) progress and no-spend streaks, delete. */
class GoalService(private val goals: GoalRepository, private val leaks: LeakService, private val clock: Clock = Clock.systemUTC()) {

    fun today(zone: ZoneId): LocalDate = LocalDate.now(clock.withZone(zone))

    suspend fun create(userId: UUID, zone: ZoneId, request: GoalRequest): GoalOutcome {
        val lensId = LensId.parse(request.lens)?.takeIf { leaks.matcher.catalogue.byId(it) != null } ?: return GoalOutcome.UnknownLens(request.lens)
        val today = today(zone)
        val baseline = if (request.kind == GoalKind.CUT_PERCENT) {
            val months = Goal.baselineMonths(today)
            val total = leaks.facts(userId, months, zone).filter { lensId in it.lenses }.fold(BigDecimal.ZERO) { a, f -> a + f.signed }
            if (total.signum() <= 0) return GoalOutcome.NoBaseline(months)
            Goal.perPeriod(total, months, request.period)
        } else null
        val goal = Goal(
            userId = userId, lens = lensId, kind = request.kind, value = request.value, period = request.period, baseline = baseline,
            target = Goal.targetOf(request.kind, request.value, baseline), startedOn = today, createdAt = clock.instant()
        )
        val saved = try { goals.save(goal) } catch (e: DuplicateGoalException) { return GoalOutcome.Duplicate(e.message ?: "That goal already exists") }
        return GoalOutcome.Saved(progress(saved, zone, today))
    }

    suspend fun list(userId: UUID, zone: ZoneId): List<GoalProgress> {
        val today = today(zone)
        return goals.findByUser(userId).map { progress(it, zone, today) }
    }

    suspend fun delete(userId: UUID, id: UUID): Boolean = goals.delete(userId, id)

    private suspend fun progress(goal: Goal, zone: ZoneId, today: LocalDate): GoalProgress {
        val earliestPeriod = goal.period.rangeOf(today).let { r ->
            (1 until GoalMath.MAX_PERIODS).fold(r.from) { from, _ -> goal.period.rangeOf(from.minusDays(1)).from }
        }
        val lookback = GoalMath.lookback(today)
        val from = minOf(maxOf(earliestPeriod, goal.period.rangeOf(goal.startedOn).from), lookback)
        val facts = leaks.facts(goal.userId, DateRange(from, today), zone)
        val label = leaks.matcher.catalogue.byId(goal.lens)?.label ?: goal.lens.value
        // Streaks count from the first day with any data (any spend or refund, any lens), at most a year back.
        val since = maxOf(lookback, facts.minOfOrNull { it.local.toLocalDate() } ?: today)
        return GoalProgress(goal, label, GoalMath.periods(goal, today, facts), GoalMath.streak(goal.lens, facts, since, today))
    }
}
