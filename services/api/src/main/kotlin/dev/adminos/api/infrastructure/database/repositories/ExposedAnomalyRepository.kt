package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.agent.*
import dev.adminos.api.domain.common.CursorCodec
import dev.adminos.api.domain.common.DuplicateEntityException
import dev.adminos.api.infrastructure.database.tables.AnomaliesTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import dev.adminos.api.infrastructure.database.dbQuery
import java.time.Instant
import java.util.UUID

class ExposedAnomalyRepository : AnomalyRepository {

    override suspend fun save(anomaly: Anomaly): Anomaly = dbQuery {
        // ON CONFLICT DO NOTHING on uq_anomalies_transaction (V20): a second anomaly for one transaction is refused cleanly.
        val inserted = AnomaliesTable.insertIgnore {
            it[id] = anomaly.id
            it[userId] = anomaly.userId
            it[transactionId] = anomaly.transactionId
            it[type] = anomaly.type.name.lowercase()
            it[confidenceScore] = anomaly.confidenceScore.toBigDecimal()
            it[reason] = anomaly.reason
            it[agentExplanation] = anomaly.agentExplanation
            it[status] = anomaly.status.name.lowercase()
            it[resolvedAt] = anomaly.resolvedAt
            it[resolvedBy] = anomaly.resolvedBy?.name?.lowercase()
            it[rulesFired] = anomaly.rulesFired.takeIf { r -> r.isNotEmpty() }?.sorted()?.joinToString(",")
            it[merchantKey] = anomaly.merchantKey
            it[createdAt] = anomaly.createdAt
            it[updatedAt] = anomaly.updatedAt
        }.insertedCount
        if (inserted == 0) throw DuplicateEntityException("Transaction ${anomaly.transactionId} already has an anomaly")
        anomaly
    }

    override suspend fun findByTransactionId(transactionId: UUID): Anomaly? = dbQuery {
        AnomaliesTable.selectAll().where { AnomaliesTable.transactionId eq transactionId }.singleOrNull()?.toAnomaly()
    }

    override suspend fun findById(id: UUID): Anomaly? = dbQuery {
        AnomaliesTable.selectAll().where { AnomaliesTable.id eq id }.singleOrNull()?.toAnomaly()
    }

    override suspend fun findByUserId(
        userId: UUID, status: AnomalyStatus?, cursor: String?, limit: Int
    ): List<Anomaly> = dbQuery {
        val query = AnomaliesTable.selectAll().where { AnomaliesTable.userId eq userId }
        status?.let { s -> query.andWhere { AnomaliesTable.status eq s.name.lowercase() } }
        cursor?.let { c ->
            val (key, _) = CursorCodec.decode(c)
            val cursorInstant = Instant.parse(key)
            query.andWhere { AnomaliesTable.createdAt less cursorInstant }
        }
        query.orderBy(AnomaliesTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toAnomaly() }
    }

    override suspend fun countByUserId(userId: UUID, status: AnomalyStatus?): Long = dbQuery {
        val query = AnomaliesTable.selectAll().where { AnomaliesTable.userId eq userId }
        status?.let { s -> query.andWhere { AnomaliesTable.status eq s.name.lowercase() } }
        query.count()
    }

    override suspend fun update(anomaly: Anomaly): Anomaly = dbQuery {
        val now = Instant.now()
        AnomaliesTable.update({ AnomaliesTable.id eq anomaly.id }) {
            it[agentExplanation] = anomaly.agentExplanation
            it[status] = anomaly.status.name.lowercase()
            it[resolvedAt] = anomaly.resolvedAt
            it[resolvedBy] = anomaly.resolvedBy?.name?.lowercase()
            it[updatedAt] = now
        }
        anomaly.copy(updatedAt = now)
    }

    private fun ResultRow.toAnomaly() = Anomaly(
        id = this[AnomaliesTable.id],
        userId = this[AnomaliesTable.userId],
        transactionId = this[AnomaliesTable.transactionId],
        type = AnomalyType.valueOf(this[AnomaliesTable.type].uppercase()),
        confidenceScore = this[AnomaliesTable.confidenceScore].toDouble(),
        reason = this[AnomaliesTable.reason],
        agentExplanation = this[AnomaliesTable.agentExplanation],
        status = AnomalyStatus.valueOf(this[AnomaliesTable.status].uppercase()),
        resolvedAt = this[AnomaliesTable.resolvedAt],
        resolvedBy = this[AnomaliesTable.resolvedBy]?.let { AnomalyResolver.valueOf(it.uppercase()) },
        rulesFired = this[AnomaliesTable.rulesFired]?.split(",")?.filter { it.isNotBlank() }?.toSet().orEmpty(),
        merchantKey = this[AnomaliesTable.merchantKey],
        createdAt = this[AnomaliesTable.createdAt],
        updatedAt = this[AnomaliesTable.updatedAt]
    )
}
