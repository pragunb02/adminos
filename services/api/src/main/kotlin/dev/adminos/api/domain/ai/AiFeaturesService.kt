package dev.adminos.api.domain.ai

import dev.adminos.api.domain.ai.eval.AiEvalKey
import dev.adminos.api.domain.ai.eval.AiEvalRecord
import dev.adminos.api.domain.ai.eval.AiEvalRecords
import dev.adminos.api.domain.audit.ActorType
import dev.adminos.api.domain.audit.AuditService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.util.UUID

/** Which provider and model serve a feature on this server, and where its data goes, in words. */
data class AiServing(val provider: AiProviderKind, val model: String, val destination: String)

/** One AI feature as Settings shows it (AOS-704). */
data class AiFeatureStatus(
    val feature: AiFeature,
    val notice: AiNotice,
    /** The user's choice; false until they turn it on. */
    val enabled: Boolean,
    /** How the server runs it, or null when the server has it off (it can't be turned on then). */
    val serving: AiServing?,
    /** The eval that allowed this provider/model/prompt (D-68), or null when off. */
    val evaluation: AiEvalRecord?,
    /** This user's spend on this feature this UTC month. */
    val spentThisMonthMicros: Long,
    /** The server's monthly cap, shared by every feature. */
    val monthlyCapMicros: Long
) {
    val available: Boolean get() = serving != null
}

/** Why a consent change was refused (the API layer maps it to a status). */
sealed class AiFeatureError(message: String) : RuntimeException(message) {
    /** The server has this feature off (not configured or not evaluated), so the user can't turn it on. */
    class NotAvailable(feature: AiFeature) : AiFeatureError("${feature.key} isn't set up on this server")
}

/**
 * Settings → AI features (AOS-704, D-67, DPDP s.5–6): each feature with its notice, where its data goes,
 * whether the server runs it, this month's spend, and the user's choice. Turning a feature on is allowed
 * only when the server runs it (which already means its eval passed, D-68); turning it off always works.
 * Every change is written to the audit log as consent evidence.
 */
class AiFeaturesService(
    private val gateway: AiGateway,
    private val tasks: Map<AiFeature, AiTask<*, *>>,
    private val consents: AiConsentRepository,
    private val calls: AiCallLogRepository,
    private val evals: AiEvalRecords,
    private val costs: AiCostGuard,
    private val audit: AuditService,
    private val clock: Clock = Clock.systemUTC()
) {
    init {
        require(tasks.keys == AiFeature.entries.toSet()) { "Every AI feature has a task: missing ${AiFeature.entries.toSet() - tasks.keys}" }
    }

    suspend fun list(userId: UUID): List<AiFeatureStatus> {
        val chosen = consents.findAll(userId).associateBy { it.feature }
        return AiFeature.entries.map { status(userId, it, chosen[it]?.enabled == true) }
    }

    suspend fun setEnabled(userId: UUID, feature: AiFeature, enabled: Boolean): AiFeatureStatus {
        if (enabled && gateway.route(feature) == null) throw AiFeatureError.NotAvailable(feature)
        val before = consents.find(userId, feature)?.enabled == true
        consents.save(AiConsent(userId, feature, enabled, clock.instant()))
        audit.log(
            userId = userId, actor = ActorType.USER, action = CONSENT_ACTION, entityType = "ai_feature",
            beforeState = JsonObject(mapOf("enabled" to JsonPrimitive(before))),
            afterState = JsonObject(mapOf("enabled" to JsonPrimitive(enabled))),
            metadata = JsonObject(mapOf("feature" to JsonPrimitive(feature.key)))
        )
        return status(userId, feature, enabled)
    }

    private suspend fun status(userId: UUID, feature: AiFeature, enabled: Boolean): AiFeatureStatus {
        val task = tasks.getValue(feature)
        val route = gateway.route(feature)
        return AiFeatureStatus(
            feature = feature,
            notice = task.notice,
            enabled = enabled,
            serving = route?.let { AiServing(it.provider.kind, it.model, destination(it.provider.kind)) },
            evaluation = route?.let { evals.find(AiEvalKey(feature, it.provider.kind, it.model, task.promptVersion)) },
            spentThisMonthMicros = calls.spentMicros(userId, feature, costs.currentMonth()),
            monthlyCapMicros = costs.capMicros
        )
    }

    companion object {
        const val CONSENT_ACTION = "ai.consent"

        /** Where a provider sends the data, in the words the notice uses. */
        fun destination(provider: AiProviderKind): String = when (provider) {
            AiProviderKind.ANTHROPIC -> "Anthropic's Claude API: a cloud service outside India, over HTTPS"
            AiProviderKind.OLLAMA -> "The Ollama model on your own AdminOS server: it doesn't leave your server"
        }
    }
}
