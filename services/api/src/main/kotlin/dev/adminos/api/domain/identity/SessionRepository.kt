package dev.adminos.api.domain.identity

import java.util.UUID

interface SessionRepository {
    suspend fun findByTokenHash(tokenHash: String): Session?
    suspend fun findActiveByUserId(userId: UUID): List<Session>
    suspend fun save(session: Session): Session
    suspend fun revoke(sessionId: UUID)
    suspend fun updateLastActive(sessionId: UUID)
}
