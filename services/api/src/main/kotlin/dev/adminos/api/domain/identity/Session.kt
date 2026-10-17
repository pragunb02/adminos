package dev.adminos.api.domain.identity

import java.time.Instant
import java.util.UUID

/** What a request's session may do (AOS-617): everything, only finish the passkey step, or nothing. */
enum class SessionAccess { FULL, PENDING_SECOND_FACTOR, DENIED }

data class Session(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val tokenHash: String,
    val deviceId: UUID? = null,
    val ipAddress: String? = null,
    val userAgent: String? = null,
    val expiresAt: Instant,
    val lastActiveAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val revokedAt: Instant? = null,
    /** When this session passed the second factor (passkey or recovery code; AOS-617). Null = still pending. */
    val secondFactorAt: Instant? = null
) {
    val isActive: Boolean
        get() = revokedAt == null && expiresAt.isAfter(Instant.now())
}
