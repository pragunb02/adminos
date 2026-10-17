package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.grouping.MerchantAlias
import dev.adminos.api.domain.financial.grouping.MerchantAliasRepository
import dev.adminos.api.domain.financial.grouping.RejectedPair
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.MerchantAliasesTable as A
import dev.adminos.api.infrastructure.database.tables.MerchantMergeRejectionsTable as R
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.util.UUID

/** V35 `merchant_aliases` and `merchant_merge_rejections` (AOS-709). */
class ExposedMerchantAliasRepository : MerchantAliasRepository {

    override suspend fun aliases(userId: UUID): List<MerchantAlias> = dbQuery {
        A.selectAll().where { A.userId eq userId }.map {
            MerchantAlias(it[A.userId], it[A.aliasKey], it[A.canonicalKey], it[A.canonicalName], it[A.createdAt])
        }
    }

    override suspend fun rejections(userId: UUID): Set<RejectedPair> = dbQuery {
        R.selectAll().where { R.userId eq userId }.map { RejectedPair(it[R.firstKey], it[R.secondKey]) }.toSet()
    }

    /** One transaction: un-alias the kept merchant, re-point the members' aliases to it, alias each member. */
    override suspend fun merge(userId: UUID, canonicalKey: String, canonicalName: String, members: Set<String>, at: Instant) = dbQuery {
        val others = members - canonicalKey
        // The kept merchant stops being anyone's alias first, so re-pointing never makes it its own alias.
        A.deleteWhere { (A.userId eq userId) and (A.aliasKey eq canonicalKey) }
        if (others.isNotEmpty()) {
            A.update({ (A.userId eq userId) and (A.canonicalKey inList others) }) {
                it[A.canonicalKey] = canonicalKey; it[A.canonicalName] = canonicalName
            }
        }
        for (key in others) {
            A.upsert(A.userId, A.aliasKey, onUpdateExclude = listOf(A.createdAt)) {
                it[A.userId] = userId; it[aliasKey] = key; it[A.canonicalKey] = canonicalKey; it[A.canonicalName] = canonicalName; it[createdAt] = at
            }
        }
        Unit
    }

    override suspend fun reject(userId: UUID, pairs: Set<RejectedPair>, at: Instant) = dbQuery {
        for (p in pairs) {
            R.insertIgnore { it[R.userId] = userId; it[firstKey] = p.first; it[secondKey] = p.second; it[createdAt] = at }
        }
    }
}
