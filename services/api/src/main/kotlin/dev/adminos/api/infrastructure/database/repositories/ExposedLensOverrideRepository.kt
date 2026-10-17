package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.coach.lenses.LensOverride
import dev.adminos.api.domain.coach.lenses.LensOverrideRepository
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.LensOverridesTable as T
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID

/** Postgres [LensOverrideRepository] (AOS-901). */
class ExposedLensOverrideRepository : LensOverrideRepository {

    override suspend fun upsert(override: LensOverride): LensOverride = dbQuery {
        T.upsert(T.userId, T.lensId, T.merchantKey) {
            it[userId] = override.userId; it[lensId] = override.lensId.value; it[merchantKey] = override.merchantKey
            it[merchant] = override.merchant; it[include] = override.include; it[updatedAt] = override.updatedAt
        }
        override
    }

    override suspend fun findByUser(userId: UUID): List<LensOverride> = dbQuery {
        T.selectAll().where { T.userId eq userId }.orderBy(T.lensId to SortOrder.ASC, T.merchantKey to SortOrder.ASC).map { it.toOverride() }
    }

    override suspend fun delete(userId: UUID, lensId: LensId, merchantKey: String): Boolean = dbQuery {
        T.deleteWhere { (T.userId eq userId) and (T.lensId eq lensId.value) and (T.merchantKey eq merchantKey) } > 0
    }

    private fun ResultRow.toOverride() = LensOverride(
        userId = this[T.userId], lensId = LensId(this[T.lensId]), merchantKey = this[T.merchantKey],
        merchant = this[T.merchant], include = this[T.include], updatedAt = this[T.updatedAt]
    )
}
