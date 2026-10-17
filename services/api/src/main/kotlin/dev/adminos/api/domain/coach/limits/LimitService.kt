package dev.adminos.api.domain.coach.limits

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.leaks.LeakFact
import dev.adminos.api.domain.coach.leaks.LeakService
import dev.adminos.api.domain.coach.lenses.LensId
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** A limit as the owner asks for it; the service resolves its label and checks the learning month. */
data class LimitRequest(val scope: LimitScope, val period: LimitPeriod, val amount: BigDecimal)

/** Everything the leaks page and Settings show about limits. */
data class LimitOverview(val today: LocalDate, val learning: LearningState, val statuses: List<LimitStatus>, val suggestions: List<LimitSuggestion>)

sealed interface LimitOutcome {
    data class Saved(val limit: SpendingLimit) : LimitOutcome
    data class StillLearning(val learning: LearningState) : LimitOutcome
    data class Duplicate(val message: String) : LimitOutcome
    data class UnknownLens(val id: String) : LimitOutcome
    data object NotFound : LimitOutcome
}

/**
 * Limits (AOS-904): the owner's own, plus suggestions after the learning month. Statuses are computed
 * from the same leak facts as the leaks page (AOS-902), for the period containing today in the user's zone.
 */
class LimitService(
    private val limits: SpendingLimitRepository,
    private val ledger: LimitAlertLedger,
    private val dismissals: SuggestionDismissals,
    private val leaks: LeakService,
    private val history: SpendHistory,
    private val clock: Clock = Clock.systemUTC()
) {
    fun today(zone: ZoneId): LocalDate = LocalDate.now(clock.withZone(zone))

    suspend fun learning(userId: UUID, zone: ZoneId): LearningState = LearningState.of(history.firstSpendAt(userId), today(zone), zone)

    suspend fun overview(userId: UUID, zone: ZoneId): LimitOverview {
        val today = today(zone)
        val learning = learning(userId, zone)
        val mine = limits.findByUser(userId)
        val suggestions = if (!learning.ready) emptyList() else {
            val weeks = LimitSuggester.weeksBefore(today)
            LimitSuggester.suggest(LimitSuggester.Input(
                today = today, now = clock.instant(), lenses = leaks.matcher.lenses,
                facts = leaks.facts(userId, DateRange(weeks.first().from, weeks.last().to), zone),
                lensesWithLimits = mine.mapNotNull { (it.scope as? LimitScope.Lens)?.id }.toSet(),
                dismissed = dismissals.dismissedAt(userId)
            ))
        }
        return LimitOverview(today, learning, statuses(userId, zone, mine), suggestions)
    }

    /** Where each of the user's limits stands today. */
    suspend fun statuses(userId: UUID, zone: ZoneId, mine: List<SpendingLimit>? = null): List<LimitStatus> {
        val list = mine ?: limits.findByUser(userId)
        if (list.isEmpty()) return emptyList()
        val today = today(zone)
        val facts = factsCovering(userId, zone, today, list.map { it.period }.toSet())
        return list.map { l -> LimitMath.status(l, today, facts.filter { inRange(it, l.period.rangeOf(today)) }) }
    }

    suspend fun create(userId: UUID, zone: ZoneId, request: LimitRequest): LimitOutcome {
        val learning = learning(userId, zone)
        if (!learning.ready) return LimitOutcome.StillLearning(learning)
        val label = labelOf(request.scope) ?: return LimitOutcome.UnknownLens(request.scope.value)
        val now = clock.instant()
        val limit = try {
            limits.save(SpendingLimit(userId = userId, scope = request.scope, label = label, period = request.period, amount = request.amount, createdAt = now, updatedAt = now))
        } catch (e: DuplicateLimitException) {
            return LimitOutcome.Duplicate(e.message ?: "That limit already exists")
        }
        armSilently(limit, zone)
        return LimitOutcome.Saved(limit)
    }

    suspend fun update(userId: UUID, zone: ZoneId, id: UUID, amount: BigDecimal?, period: LimitPeriod?): LimitOutcome {
        val existing = limits.findById(userId, id) ?: return LimitOutcome.NotFound
        val changed = existing.copy(amount = amount ?: existing.amount, period = period ?: existing.period, updatedAt = clock.instant())
        val saved = try { limits.update(changed) } catch (e: DuplicateLimitException) { return LimitOutcome.Duplicate(e.message ?: "That limit already exists") }
        armSilently(saved, zone)
        return LimitOutcome.Saved(saved)
    }

    suspend fun delete(userId: UUID, id: UUID): Boolean = limits.delete(userId, id)

    /** "Not now" on a suggestion: hidden for [LimitSuggester.SNOOZE]. @return false for an unknown lens */
    suspend fun dismiss(userId: UUID, lens: String): Boolean {
        val id = LensId.parse(lens)?.takeIf { leaks.matcher.catalogue.byId(it) != null } ?: return false
        dismissals.dismiss(userId, id, clock.instant())
        return true
    }

    /**
     * Thresholds already passed when a limit is set or changed are recorded without a push: setting a
     * limit you're already over shouldn't nag you that same minute.
     */
    private suspend fun armSilently(limit: SpendingLimit, zone: ZoneId) {
        val status = statuses(limit.userId, zone, listOf(limit)).single()
        status.reached.forEach { ledger.claim(limit.id, status.period.from, it, clock.instant()) }
    }

    private suspend fun factsCovering(userId: UUID, zone: ZoneId, today: LocalDate, periods: Set<LimitPeriod>): List<LeakFact> {
        val from = periods.minOf { it.rangeOf(today).from }
        return leaks.facts(userId, DateRange(from, today), zone)
    }

    private fun inRange(f: LeakFact, r: DateRange) = f.local.toLocalDate().let { !it.isBefore(r.from) && !it.isAfter(r.to) }

    private fun labelOf(scope: LimitScope): String? = when (scope) {
        is LimitScope.Lens -> leaks.matcher.catalogue.byId(scope.id)?.label
        is LimitScope.Merchant -> scope.name.trim()
        is LimitScope.Category -> scope.category.name.lowercase().replaceFirstChar { it.uppercase() }.let { if (it == "Emi") "EMI" else it }
    }
}
