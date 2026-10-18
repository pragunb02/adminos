package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.review.GoldenCandidate
import dev.adminos.api.domain.financial.review.GoldenCandidateRepository
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.GoldenCandidatesTable as T
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** The local golden set on Postgres (AOS-708). Duplicates are ignored by the unique index. */
class ExposedGoldenCandidateRepository : GoldenCandidateRepository {

    override suspend fun add(candidate: GoldenCandidate): Boolean = dbQuery {
        T.insertIgnore {
            it[id] = candidate.id
            it[userId] = candidate.userId
            it[merchantRaw] = candidate.merchantRaw
            it[paymentMethod] = candidate.paymentMethod?.name?.lowercase()
            it[direction] = candidate.direction.name.lowercase()
            it[category] = candidate.category.name.lowercase()
            it[createdAt] = candidate.createdAt
        }.insertedCount > 0
    }

    override suspend fun list(userId: UUID): List<GoldenCandidate> = dbQuery {
        T.selectAll().where { T.userId eq userId }.orderBy(T.createdAt to SortOrder.ASC, T.id to SortOrder.ASC).map { it.toCandidate() }
    }

    override suspend fun delete(userId: UUID, id: UUID): Boolean = dbQuery { T.deleteWhere { (T.userId eq userId) and (T.id eq id) } > 0 }

    private fun ResultRow.toCandidate() = GoldenCandidate(
        id = this[T.id],
        userId = this[T.userId],
        merchantRaw = this[T.merchantRaw],
        paymentMethod = this[T.paymentMethod]?.let { PaymentMethod.valueOf(it.uppercase()) },
        direction = TransactionType.valueOf(this[T.direction].uppercase()),
        category = TransactionCategory.valueOf(this[T.category].uppercase()),
        createdAt = this[T.createdAt]
    )
}
