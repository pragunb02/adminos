package dev.adminos.api.domain.ingestion

import kotlinx.serialization.Serializable

@Serializable
data class SmsBatchRequest(
    val deviceId: String,
    val batch: List<SmsRecord>
)

@Serializable
data class SmsRecord(
    val merchant: String,
    val amount: Double,
    val date: String,
    val accountLast4: String,
    val paymentMethod: String? = null,
    val rawText: String? = null // Must be null — rejected if present
)

@Serializable
data class SyncSessionResponse(
    val id: String,
    val connectionId: String? = null,
    val syncType: String,
    val status: String,
    val totalItems: Int = 0,
    val processedItems: Int = 0,
    val failedItems: Int = 0,
    val duplicateItems: Int = 0,
    val netNewItems: Int = 0,
    val startedAt: String? = null,
    val completedAt: String? = null
)

@Serializable
data class SmsBatchResponse(
    val syncSessionId: String,
    val status: String,
    val totalItems: Int
)
