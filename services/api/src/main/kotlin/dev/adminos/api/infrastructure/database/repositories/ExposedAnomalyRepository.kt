package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.agent.*
import dev.adminos.api.infrastructure.database.CursorCodec
import dev.adminos.api.infrastructure.database.tables.AnomaliesTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.util.UUID

class ExposedAnomalyRepository : AnomalyRepository {

    override suspend fun save(anomaly: Anomaly): Anomaly = newSuspendedTransaction {
        AnomaliesTable.insert {
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
            it[createdAt] = anomaly.createdAt
            it[updatedAt] = anomaly.updatedAt
        }
        anomaly
    }

    override suspend fun findById(id: UUID): Anomaly? = newSuspendedTransaction {
        AnomaliesTable.select { AnomaliesTable.id eq id }.singleOrNull()?.toAnomaly()
    }

    override suspend fun findByUserId(
        userId: UUID, status: AnomalyStatus?, cursor: String?, limit: Int
    ): List<Anomaly> = newSuspendedTransaction {
        val query = AnomaliesTable.select { AnomaliesTable.userId eq userId }
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

    override suspend fun countByUserId(userId: UUID, status: AnomalyStatus?): Long = newSuspendedTransaction {
        val query = AnomaliesTable.select { AnomaliesTable.userId eq userId }
        status?.let { s -> query.andWhere { AnomaliesTable.status eq s.name.lowercase() } }
        query.count()
    }

    override suspend fun update(anomaly: Anomaly): Anomaly = newSuspendedTransaction {
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
        createdAt = this[AnomaliesTable.createdAt],
        updatedAt = this[AnomaliesTable.updatedAt]
    )
}
