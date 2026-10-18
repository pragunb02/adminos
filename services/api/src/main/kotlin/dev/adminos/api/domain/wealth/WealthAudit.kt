package dev.adminos.api.domain.wealth

import dev.adminos.api.domain.audit.ActorType
import dev.adminos.api.domain.audit.AuditService
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/** Wealth audit actions (D-79, WEALTH-RESEARCH §6.6). */
object WealthAuditActions {
    const val IMPORT = "wealth.import"
    const val IMPORT_UNDO = "wealth.import_undo"
    const val IMPORT_REJECTED = "wealth.import_rejected"
    const val PRICE_FETCH = "wealth.price_fetch"
}

/**
 * The wealth audit trail. Metadata is **codes and counts only**: the signature takes numbers and short codes, so an
 * identifier, an amount or a scheme name can't be written to the audit log by mistake.
 */
class WealthAudit(private val audit: AuditService) {

    /** An action by the owner on an entity of theirs, with numeric facts (e.g. rows added) and short codes (e.g. a reader id). */
    fun record(userId: UUID?, action: String, entityType: String, entityId: UUID?, counts: Map<String, Int> = emptyMap(), codes: Map<String, String> = emptyMap()) {
        require(action.startsWith("wealth.")) { "wealth audit actions start with wealth." }
        codes.values.forEach { require(it.matches(CODE)) { "audit codes are short identifiers, never content" } }
        val metadata = buildJsonObject {
            counts.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            codes.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        audit.log(userId = userId, actor = if (userId == null) ActorType.SYSTEM else ActorType.USER, action = action,
            entityType = entityType, entityId = entityId, metadata = metadata)
    }

    private companion object {
        val CODE = Regex("[a-z0-9_.-]{1,40}")
    }
}
