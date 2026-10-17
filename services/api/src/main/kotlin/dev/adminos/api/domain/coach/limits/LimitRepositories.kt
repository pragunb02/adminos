package dev.adminos.api.domain.coach.limits

import dev.adminos.api.domain.coach.lenses.LensId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Thrown when a limit for the same (user, scope, period) already exists. */
class DuplicateLimitException(message: String) : RuntimeException(message)

interface SpendingLimitRepository {
    /** @throws DuplicateLimitException when the user already has a limit for this scope and period */
    suspend fun save(limit: SpendingLimit): SpendingLimit
    /** @throws DuplicateLimitException when the change would clash with another of the user's limits */
    suspend fun update(limit: SpendingLimit): SpendingLimit
    suspend fun findByUser(userId: UUID): List<SpendingLimit>
    /** Only the user's own limit: another user's id is not found. */
    suspend fun findById(userId: UUID, id: UUID): SpendingLimit?
    /** @return whether there was one */
    suspend fun delete(userId: UUID, id: UUID): Boolean
}

/**
 * Which limit thresholds were already announced (AOS-904): one row per (limit, period start, threshold).
 * [claim] is the only way to send: it records the threshold if it isn't yet, atomically, so two instances
 * or a restart can never announce it twice.
 */
interface LimitAlertLedger {
    /** @return true if this call recorded it (go ahead and announce), false if it was already recorded */
    suspend fun claim(limitId: UUID, periodStart: LocalDate, threshold: Threshold, at: Instant): Boolean
    suspend fun claimed(limitId: UUID, periodStart: LocalDate): Set<Threshold>
}

/** Lens suggestions the owner said "Not now" to, and when. */
interface SuggestionDismissals {
    suspend fun dismiss(userId: UUID, lens: LensId, at: Instant)
    suspend fun dismissedAt(userId: UUID): Map<LensId, Instant>
}

class InMemorySpendingLimitRepository : SpendingLimitRepository {
    private val store = ConcurrentHashMap<UUID, SpendingLimit>()

    override suspend fun save(limit: SpendingLimit): SpendingLimit = put(limit)
    override suspend fun update(limit: SpendingLimit): SpendingLimit = put(limit)

    @Synchronized
    private fun put(limit: SpendingLimit): SpendingLimit {
        val clash = store.values.any { it.id != limit.id && it.userId == limit.userId && it.period == limit.period && it.scope.kind == limit.scope.kind && it.scope.value == limit.scope.value }
        if (clash) throw DuplicateLimitException("There is already a ${limit.period.word} limit for ${limit.label}")
        store[limit.id] = limit
        return limit
    }

    override suspend fun findByUser(userId: UUID) = store.values.filter { it.userId == userId }.sortedBy { it.createdAt }
    override suspend fun findById(userId: UUID, id: UUID) = store[id]?.takeIf { it.userId == userId }
    override suspend fun delete(userId: UUID, id: UUID): Boolean = findById(userId, id)?.let { store.remove(id) != null } ?: false
}

class InMemoryLimitAlertLedger : LimitAlertLedger {
    private val sent = ConcurrentHashMap.newKeySet<Triple<UUID, LocalDate, Threshold>>()
    override suspend fun claim(limitId: UUID, periodStart: LocalDate, threshold: Threshold, at: Instant) = sent.add(Triple(limitId, periodStart, threshold))
    override suspend fun claimed(limitId: UUID, periodStart: LocalDate) = sent.filter { it.first == limitId && it.second == periodStart }.map { it.third }.toSet()
}

class InMemorySuggestionDismissals : SuggestionDismissals {
    private val store = ConcurrentHashMap<Pair<UUID, LensId>, Instant>()
    override suspend fun dismiss(userId: UUID, lens: LensId, at: Instant) { store[userId to lens] = at }
    override suspend fun dismissedAt(userId: UUID) = store.filterKeys { it.first == userId }.mapKeys { it.key.second }
}
