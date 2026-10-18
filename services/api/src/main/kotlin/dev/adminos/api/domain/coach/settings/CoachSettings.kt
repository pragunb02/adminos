package dev.adminos.api.domain.coach.settings

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The owner's coach switches (Epic E9, D-80: every nudge is configurable). Kept apart from the
 * notification preferences so the coach can grow without touching them; a notification still also
 * needs its channel switched on there.
 */
data class CoachSettings(
    val userId: UUID,
    /** 50/80/100% limit pushes (AOS-904). */
    val limitAlerts: Boolean = true,
    /** The evening digest (AOS-905): on, at [digestTime] in the user's zone, optionally silent on a zero day. */
    val eveningDigest: Boolean = true,
    val digestTime: java.time.LocalTime = DEFAULT_DIGEST_TIME,
    val digestSkipZeroDay: Boolean = false,
    val updatedAt: Instant = Instant.EPOCH
) {
    companion object {
        /** The owner's choice (2026-10-17). */
        val DEFAULT_DIGEST_TIME: java.time.LocalTime = java.time.LocalTime.of(21, 30)
        fun defaultFor(userId: UUID) = CoachSettings(userId)
    }
}

interface CoachSettingsRepository {
    suspend fun find(userId: UUID): CoachSettings?
    suspend fun save(settings: CoachSettings): CoachSettings
}

class InMemoryCoachSettingsRepository : CoachSettingsRepository {
    private val store = ConcurrentHashMap<UUID, CoachSettings>()
    override suspend fun find(userId: UUID) = store[userId]
    override suspend fun save(settings: CoachSettings): CoachSettings { store[settings.userId] = settings; return settings }
}

/** Reads with defaults; never fails for a user without a row. */
class CoachSettingsService(private val repository: CoachSettingsRepository, private val clock: java.time.Clock = java.time.Clock.systemUTC()) {
    suspend fun of(userId: UUID): CoachSettings = repository.find(userId) ?: CoachSettings.defaultFor(userId)

    suspend fun update(userId: UUID, change: (CoachSettings) -> CoachSettings): CoachSettings =
        repository.save(change(of(userId)).copy(userId = userId, updatedAt = clock.instant()))
}
