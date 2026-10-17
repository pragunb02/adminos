package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ai.AiConsent
import dev.adminos.api.domain.ai.AiConsentRepository
import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.AiConsentsTable as T
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import java.util.UUID

/** Per-feature AI consent on Postgres (AOS-704). A row for a feature this build doesn't know is ignored, never "on". */
class ExposedAiConsentRepository : AiConsentRepository {
    private val logger = LoggerFactory.getLogger(ExposedAiConsentRepository::class.java)

    override suspend fun find(userId: UUID, feature: AiFeature): AiConsent? = dbQuery {
        T.selectAll().where { (T.userId eq userId) and (T.feature eq feature.key) }.singleOrNull()?.toConsent()
    }

    override suspend fun findAll(userId: UUID): List<AiConsent> = dbQuery {
        T.selectAll().where { T.userId eq userId }.mapNotNull { it.toConsent() }.sortedBy { it.feature }
    }

    override suspend fun save(consent: AiConsent) {
        dbQuery {
            T.upsert(T.userId, T.feature) {
                it[userId] = consent.userId
                it[feature] = consent.feature.key
                it[enabled] = consent.enabled
                it[updatedAt] = consent.updatedAt
            }
        }
    }

    private fun ResultRow.toConsent(): AiConsent? {
        val feature = AiFeature.fromKey(this[T.feature]) ?: run {
            logger.warn("Ignoring AI consent for unknown feature {}", this[T.feature])
            return null
        }
        return AiConsent(this[T.userId], feature, this[T.enabled], this[T.updatedAt])
    }
}
