package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.agent.*
import dev.adminos.api.infrastructure.database.tables.InsightsTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.UUID

class ExposedInsightRepository : InsightRepository {

    override suspend fun save(insight: Insight): Insight = newSuspendedTransaction {
        InsightsTable.insert {
            it[id] = insight.id
            it[userId] = insight.userId
            it[briefingId] = insight.briefingId
            it[type] = insight.type.name.lowercase()
            it[title] = insight.title
            it[body] = insight.body
            it[severity] = insight.severity.name.lowercase()
            it[entityType] = insight.entityType
            it[entityId] = insight.entityId
            it[actionType] = insight.actionType.name.lowercase()
            it[actionPayload] = insight.actionPayload?.let { m ->
                Json.encodeToString(kotlinx.serialization.serializer(), m)
            }
            it[status] = insight.status.name.lowercase()
            it[seenAt] = insight.seenAt
            it[actedAt] = insight.actedAt
            it[dismissedAt] = insight.dismissedAt
            it[createdAt] = insight.createdAt
            it[updatedAt] = insight.updatedAt
        }
        insight
    }

    override suspend fun findByBriefingId(briefingId: UUID): List<Insight> = newSuspendedTransaction {
        InsightsTable.select { InsightsTable.briefingId eq briefingId }
            .orderBy(InsightsTable.createdAt, SortOrder.DESC)
            .map { it.toInsight() }
    }

    override suspend fun findByUserId(userId: UUID, status: InsightStatus?): List<Insight> =
        newSuspendedTransaction {
            val query = InsightsTable.select { InsightsTable.userId eq userId }
            status?.let { s -> query.andWhere { InsightsTable.status eq s.name.lowercase() } }
            query.orderBy(InsightsTable.createdAt, SortOrder.DESC)
                .map { it.toInsight() }
        }

    @Suppress("UNCHECKED_CAST")
    private fun ResultRow.toInsight() = Insight(
        id = this[InsightsTable.id],
        userId = this[InsightsTable.userId],
        briefingId = this[InsightsTable.briefingId],
        type = InsightType.valueOf(this[InsightsTable.type].uppercase()),
        title = this[InsightsTable.title],
        body = this[InsightsTable.body],
        severity = Severity.valueOf(this[InsightsTable.severity].uppercase()),
        entityType = this[InsightsTable.entityType],
        entityId = this[InsightsTable.entityId],
        actionType = InsightActionType.valueOf(this[InsightsTable.actionType].uppercase()),
        actionPayload = this[InsightsTable.actionPayload]?.let {
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(it) as? Map<String, Any?>
        },
        status = InsightStatus.valueOf(this[InsightsTable.status].uppercase()),
        seenAt = this[InsightsTable.seenAt],
        actedAt = this[InsightsTable.actedAt],
        dismissedAt = this[InsightsTable.dismissedAt],
        createdAt = this[InsightsTable.createdAt],
        updatedAt = this[InsightsTable.updatedAt]
    )
}
