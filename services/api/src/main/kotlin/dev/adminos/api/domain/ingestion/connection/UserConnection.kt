package dev.adminos.api.domain.ingestion.connection

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

data class UserConnection(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val sourceType: SourceType,
    val status: ConnectionStatus = ConnectionStatus.PENDING,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenExpiresAt: Instant? = null,
    val oauthScope: List<String>? = null,
    val gmailAddress: String? = null,
    val pubsubExpiry: Instant? = null,
    val historyId: String? = null,
    val lastSyncedAt: Instant? = null,
    val lastSyncStatus: String? = null,
    val lastError: String? = null,
    val nextSyncAt: Instant? = null,
    val totalSynced: Int = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

enum class SourceType { GMAIL, SMS, PDF, ACCOUNT_AGGREGATOR }
enum class ConnectionStatus { CONNECTED, DISCONNECTED, ERROR, PENDING }

@Serializable
data class CreateConnectionRequest(
    val sourceType: String,
    val code: String? = null,
    val redirectUri: String? = null
)

@Serializable
data class ConnectionResponse(
    val id: String,
    val sourceType: String,
    val status: String,
    val gmailAddress: String? = null,
    val lastSyncedAt: String? = null,
    val lastSyncStatus: String? = null,
    val totalSynced: Int = 0,
    val createdAt: String
) {
    companion object {
        fun from(conn: UserConnection) = ConnectionResponse(
            id = conn.id.toString(),
            sourceType = conn.sourceType.name.lowercase(),
            status = conn.status.name.lowercase(),
            gmailAddress = conn.gmailAddress,
            lastSyncedAt = conn.lastSyncedAt?.toString(),
            lastSyncStatus = conn.lastSyncStatus,
            totalSynced = conn.totalSynced,
            createdAt = conn.createdAt.toString()
        )
    }
}
