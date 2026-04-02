package dev.adminos.api.domain.audit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

data class AuditLog(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID? = null,
    val actor: ActorType,
    val action: String,
    val entityType: String,
    val entityId: UUID? = null,
    val beforeState: JsonObject? = null,
    val afterState: JsonObject? = null,
    val ipAddress: String? = null,
    val deviceId: UUID? = null,
    val metadata: JsonObject? = null,
    val createdAt: Instant = Instant.now()
)

enum class ActorType {
    USER, SYSTEM, AGENT, WORKER
}

// Well-known audit actions
object AuditActions {
    const val AUTH_LOGIN = "auth.login"
    const val AUTH_LOGOUT = "auth.logout"
    const val AUTH_REFRESH = "auth.refresh"
    const val AUTH_SESSION_REVOKE = "auth.session_revoke"
    const val CONNECTION_CREATE = "connection.create"
    const val CONNECTION_DISCONNECT = "connection.disconnect"
    const val CONNECTION_ERROR = "connection.error"
    const val SUBSCRIPTION_CANCEL = "subscription.cancel"
    const val SUBSCRIPTION_KEEP = "subscription.keep"
    const val SUBSCRIPTION_FLAG = "subscription.flag"
    const val ANOMALY_CONFIRM_SAFE = "anomaly.confirm_safe"
    const val ANOMALY_CONFIRM_FRAUD = "anomaly.confirm_fraud"
    const val ANOMALY_DISMISS = "anomaly.dismiss"
    const val INSIGHT_ACT = "insight.act"
    const val INSIGHT_DISMISS = "insight.dismiss"
    const val TRANSACTION_UPDATE_CATEGORY = "transaction.update_category"
    const val SYNC_START = "sync.start"
    const val SYNC_COMPLETE = "sync.complete"
    const val SYNC_FAIL = "sync.fail"
}
