package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.notifications.push.PushEndpoint
import dev.adminos.api.domain.notifications.push.PushKeys
import dev.adminos.api.domain.notifications.push.PushSubscription
import dev.adminos.api.domain.notifications.push.PushSubscriptionRepository
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.PushSubscriptionsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.util.UUID

/** Postgres [PushSubscriptionRepository] (AOS-510). Upsert on the unique endpoint keeps one row per device. */
class ExposedPushSubscriptionRepository : PushSubscriptionRepository {
    private val t = PushSubscriptionsTable

    override suspend fun upsert(subscription: PushSubscription): PushSubscription = dbQuery {
        t.upsert(t.endpoint, onUpdateExclude = listOf(t.id, t.createdAt)) {
            it[id] = subscription.id
            it[userId] = subscription.userId
            it[endpoint] = subscription.endpoint.url
            it[p256dh] = subscription.keys.p256dh
            it[auth] = subscription.keys.auth
            it[label] = subscription.label
            it[createdAt] = subscription.createdAt
            it[lastSuccessAt] = subscription.lastSuccessAt
        }
        t.selectAll().where { t.endpoint eq subscription.endpoint.url }.single().toSubscription()
    }

    override suspend fun findByUserId(userId: UUID): List<PushSubscription> = dbQuery {
        t.selectAll().where { t.userId eq userId }.orderBy(t.createdAt to SortOrder.ASC).map { it.toSubscription() }
    }

    override suspend fun delete(userId: UUID, endpoint: PushEndpoint): Boolean = dbQuery {
        t.deleteWhere { (t.userId eq userId) and (t.endpoint eq endpoint.url) } > 0
    }

    override suspend fun deleteById(id: UUID): Unit = dbQuery {
        t.deleteWhere { t.id eq id }
    }

    override suspend fun markSucceeded(id: UUID, at: Instant): Unit = dbQuery {
        t.update({ t.id eq id }) { it[lastSuccessAt] = at }
    }

    private fun ResultRow.toSubscription() = PushSubscription(
        id = this[t.id],
        userId = this[t.userId],
        endpoint = PushEndpoint(this[t.endpoint]),
        keys = PushKeys(this[t.p256dh], this[t.auth]),
        label = this[t.label],
        createdAt = this[t.createdAt],
        lastSuccessAt = this[t.lastSuccessAt]
    )
}
