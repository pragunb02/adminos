package dev.adminos.api.domain.audit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Service for recording audit log entries.
 * Fire-and-forget: never blocks the caller, never throws.
 * Uses a dedicated CoroutineScope so audit writes don't slow down request handling.
 */
class AuditService(
    private val repository: AuditRepository,
    private val scope: CoroutineScope
) {

    private val logger = LoggerFactory.getLogger(AuditService::class.java)

    /**
     * Log an audit entry asynchronously. Does NOT suspend the caller.
     * Errors are logged but never propagated.
     */
    fun log(
        userId: UUID? = null,
        actor: ActorType,
        action: String,
        entityType: String,
        entityId: UUID? = null,
        beforeState: JsonObject? = null,
        afterState: JsonObject? = null,
        ipAddress: String? = null,
        deviceId: UUID? = null,
        metadata: JsonObject? = null
    ) {
        val entry = AuditLog(
            userId = userId,
            actor = actor,
            action = action,
            entityType = entityType,
            entityId = entityId,
            beforeState = beforeState,
            afterState = afterState,
            ipAddress = ipAddress,
            deviceId = deviceId,
            metadata = metadata
        )

        scope.launch {
            try {
                repository.save(entry)
            } catch (e: Exception) {
                logger.error("Failed to write audit log: action={}, entity={}/{}", action, entityType, entityId, e)
            }
        }
    }
}
