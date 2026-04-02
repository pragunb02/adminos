package dev.adminos.api.domain.identity

import java.time.Instant
import java.util.UUID

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
    val revokedAt: Instant? = null
) {
    val isActive: Boolean
        get() = revokedAt == null && expiresAt.isAfter(Instant.now())
}
