package dev.adminos.api.domain.ai

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A user's choice for one AI feature (AOS-704, D-67, DPDP s.6: consent per purpose). With no row a feature
 * is **off**; a row exists once the user has turned it on or off in Settings.
 */
data class AiConsent(val userId: UUID, val feature: AiFeature, val enabled: Boolean, val updatedAt: Instant)

interface AiConsentRepository {
    suspend fun find(userId: UUID, feature: AiFeature): AiConsent?

    suspend fun findAll(userId: UUID): List<AiConsent>

    /** Insert or replace the user's choice for its feature. */
    suspend fun save(consent: AiConsent)
}

class InMemoryAiConsentRepository : AiConsentRepository {
    private val store = ConcurrentHashMap<Pair<UUID, AiFeature>, AiConsent>()

    override suspend fun find(userId: UUID, feature: AiFeature): AiConsent? = store[userId to feature]

    override suspend fun findAll(userId: UUID): List<AiConsent> = store.values.filter { it.userId == userId }.sortedBy { it.feature }

    override suspend fun save(consent: AiConsent) {
        store[consent.userId to consent.feature] = consent
    }
}

/**
 * May this user's data be used by this AI feature right now? The gateway asks before every call, and each
 * feature asks before using anything it cached from earlier answers. Production: [AiConsents]. Offline
 * tools (the eval harness, the bake-off) that run on synthetic data pass an explicit allow-all.
 */
fun interface AiConsentCheck {
    suspend fun allows(userId: UUID, feature: AiFeature): Boolean
}

/** Consent from the user's stored choice; no choice means no (off by default). */
class AiConsents(private val repository: AiConsentRepository) : AiConsentCheck {
    override suspend fun allows(userId: UUID, feature: AiFeature): Boolean = repository.find(userId, feature)?.enabled == true
}
