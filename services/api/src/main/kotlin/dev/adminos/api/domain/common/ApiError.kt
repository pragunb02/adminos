package dev.adminos.api.domain.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val details: JsonObject? = null
) {
    companion object {
        // Auth
        val TOKEN_EXPIRED = ApiError("AUTH_001", "Token expired")
        val TOKEN_INVALID = ApiError("AUTH_002", "Token invalid")
        val INSUFFICIENT_SCOPE = ApiError("AUTH_003", "Insufficient scope")
        val SESSION_REVOKED = ApiError("AUTH_004", "Session has been revoked")

        // User
        val USER_NOT_FOUND = ApiError("USER_001", "User not found")

        // Connection
        val CONNECTION_NOT_FOUND = ApiError("CONN_001", "Connection not found")
        val OAUTH_FAILED = ApiError("CONN_002", "OAuth failed")

        // Transaction
        val TRANSACTION_NOT_FOUND = ApiError("TXN_001", "Transaction not found")

        // Subscription
        val SUBSCRIPTION_NOT_FOUND = ApiError("SUB_001", "Subscription not found")

        // Bill
        val BILL_NOT_FOUND = ApiError("BILL_001", "Bill not found")

        // Sync
        val SYNC_IN_PROGRESS = ApiError("SYNC_001", "Sync already in progress")

        // Ingest
        val INVALID_FINGERPRINT = ApiError("INGEST_001", "Invalid fingerprint")
        val BATCH_TOO_LARGE = ApiError("INGEST_002", "Batch too large (max 100)")
        val RAW_TEXT_REJECTED = ApiError("INGEST_003", "Raw SMS text is not accepted. Send structured JSON only.")

        // Rate limit
        val RATE_LIMITED = ApiError("RATE_001", "Rate limit exceeded")

        // Server
        val INTERNAL_ERROR = ApiError("SERVER_001", "Internal server error")
    }
}
