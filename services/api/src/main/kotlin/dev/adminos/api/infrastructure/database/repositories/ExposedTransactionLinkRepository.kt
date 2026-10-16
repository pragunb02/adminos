package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.links.LinkKind
import dev.adminos.api.domain.financial.links.LinkRelation
import dev.adminos.api.domain.financial.links.TransactionLink
import dev.adminos.api.domain.financial.links.TransactionLinkRepository
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.TransactionLinksTable as T
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Instant
import java.util.UUID

class ExposedTransactionLinkRepository : TransactionLinkRepository {

    override suspend fun save(link: TransactionLink): TransactionLink = dbQuery {
        T.insert {
            it[id] = link.id; it[userId] = link.userId; it[transactionId] = link.transactionId
            it[relatedTransactionId] = link.relatedTransactionId; it[ingestEventId] = link.ingestEventId
            it[sourceType] = link.source; it[kind] = link.kind.name.lowercase(); it[relation] = link.relation.name.lowercase(); it[score] = link.score
            it[reason] = link.reason; it[rulesVersion] = link.rulesVersion
            it[createdAt] = link.createdAt; it[updatedAt] = link.updatedAt
        }
        link
    }

    override suspend fun findById(id: UUID): TransactionLink? = dbQuery {
        T.selectAll().where { T.id eq id }.singleOrNull()?.toLink()
    }

    override suspend fun findByUser(userId: UUID, kind: LinkKind, relation: LinkRelation?): List<TransactionLink> = dbQuery {
        val q = T.selectAll().where { (T.userId eq userId) and (T.kind eq kind.name.lowercase()) }
        relation?.let { r -> q.andWhere { T.relation eq r.name.lowercase() } }
        q.orderBy(T.createdAt, SortOrder.DESC).map { it.toLink() }
    }

    override suspend fun findByTransaction(transactionId: UUID): List<TransactionLink> = dbQuery {
        T.selectAll().where { (T.transactionId eq transactionId) or (T.relatedTransactionId eq transactionId) }.map { it.toLink() }
    }

    override suspend fun mergedSources(transactionIds: Collection<UUID>): Map<UUID, Set<String>> = dbQuery {
        if (transactionIds.isEmpty()) return@dbQuery emptyMap()
        T.select(T.transactionId, T.sourceType)
            .where { (T.transactionId inList transactionIds) and (T.kind eq LinkKind.MERGED.name.lowercase()) and (T.relation eq LinkRelation.DUPLICATE.name.lowercase()) }
            .groupBy({ it[T.transactionId] }, { it[T.sourceType] }).mapValues { it.value.toSet() }
    }

    override suspend fun update(link: TransactionLink): TransactionLink = dbQuery {
        val now = Instant.now()
        T.update({ T.id eq link.id }) {
            it[transactionId] = link.transactionId; it[relatedTransactionId] = link.relatedTransactionId
            it[ingestEventId] = link.ingestEventId; it[kind] = link.kind.name.lowercase()
            it[relation] = link.relation.name.lowercase(); it[reason] = link.reason; it[updatedAt] = now
        }
        link.copy(updatedAt = now)
    }

    private fun ResultRow.toLink() = TransactionLink(
        id = this[T.id], userId = this[T.userId], transactionId = this[T.transactionId],
        relatedTransactionId = this[T.relatedTransactionId], ingestEventId = this[T.ingestEventId],
        source = this[T.sourceType], kind = LinkKind.valueOf(this[T.kind].uppercase()),
        relation = LinkRelation.valueOf(this[T.relation].uppercase()), score = this[T.score],
        reason = this[T.reason], rulesVersion = this[T.rulesVersion], createdAt = this[T.createdAt], updatedAt = this[T.updatedAt]
    )
}
