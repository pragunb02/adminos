package dev.adminos.api.domain.ingestion.connection

import java.util.UUID

interface ConnectionRepository {
    suspend fun findById(id: UUID): UserConnection?
    suspend fun findByUserId(userId: UUID): List<UserConnection>
    suspend fun findByUserAndSource(userId: UUID, sourceType: SourceType): UserConnection?
    suspend fun findByGmailAddress(gmailAddress: String): UserConnection?
    suspend fun findConnectedBySourceType(sourceType: SourceType): List<UserConnection>
    suspend fun save(connection: UserConnection): UserConnection
    suspend fun update(connection: UserConnection): UserConnection
}
