package dev.adminos.api.domain.ingestion.connection

import java.time.Instant
import java.util.UUID

interface ConnectionRepository {
    suspend fun findById(id: UUID): UserConnection?
    suspend fun findByUserId(userId: UUID): List<UserConnection>
    suspend fun findByUserAndSource(userId: UUID, sourceType: SourceType): UserConnection?
    suspend fun findByGmailAddress(gmailAddress: String): UserConnection?
    suspend fun findConnectedBySourceType(sourceType: SourceType): List<UserConnection>
    suspend fun save(connection: UserConnection): UserConnection
    suspend fun update(connection: UserConnection): UserConnection

    /*
     * Narrow writes (AOS-405): the Go worker updates the same row (history ID, sync status, tokens)
     * while the API renews a watch, so these change only their own columns and never write back a
     * stale copy of the rest (which could rewind the history ID, D-59).
     */

    /** Stores the Gmail push registration state and nothing else. */
    suspend fun updateWatch(id: UUID, watch: GmailWatch)

    /** Stores a refreshed access token (already encrypted) and its expiry, and nothing else. */
    suspend fun updateAccessToken(id: UUID, accessToken: String, expiresAt: Instant?)

    /** Puts a connection in the error state with a short, content-free [reason] (e.g. a revoked grant). */
    suspend fun markError(id: UUID, reason: String)
}
