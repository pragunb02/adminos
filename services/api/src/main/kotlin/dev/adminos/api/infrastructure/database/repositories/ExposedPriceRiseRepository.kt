package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.PriceRise
import dev.adminos.api.domain.financial.PriceRiseRepository
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.SubscriptionPriceRisesTable as T
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/** V45 `subscription_price_rises` (AOS-715): the unique (subscription, price_key) makes a rise once. */
class ExposedPriceRiseRepository : PriceRiseRepository {

    override suspend fun recordIfNew(rise: PriceRise): Boolean = dbQuery {
        T.insertIgnore {
            it[id] = rise.id; it[userId] = rise.userId; it[subscriptionId] = rise.subscriptionId
            it[oldAmount] = rise.oldAmount; it[newAmount] = rise.newAmount; it[priceKey] = rise.priceKey; it[currency] = rise.currency
            it[chargedOn] = rise.chargedOn; it[transactionId] = rise.transactionId; it[notifiedAt] = rise.notifiedAt; it[createdAt] = rise.createdAt
        }.insertedCount > 0
    }

    override suspend fun findUnnotified(userId: UUID): List<PriceRise> = dbQuery {
        T.selectAll().where { (T.userId eq userId) and T.notifiedAt.isNull() }
            .orderBy(T.createdAt to SortOrder.ASC, T.id to SortOrder.ASC).map { it.toRise() }
    }

    override suspend fun markNotified(id: UUID, at: Instant) {
        dbQuery { T.update({ T.id eq id }) { it[notifiedAt] = at } }
    }

    private fun ResultRow.toRise() = PriceRise(
        id = this[T.id], userId = this[T.userId], subscriptionId = this[T.subscriptionId],
        oldAmount = this[T.oldAmount], newAmount = this[T.newAmount], currency = this[T.currency],
        chargedOn = this[T.chargedOn], transactionId = this[T.transactionId], notifiedAt = this[T.notifiedAt], createdAt = this[T.createdAt]
    )
}
