package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.notifications.*
import dev.adminos.api.infrastructure.database.tables.NotificationsTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import dev.adminos.api.infrastructure.database.dbQuery
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ExposedNotificationRepository : NotificationRepository {

    override suspend fun save(notification: Notification): Notification = dbQuery {
        NotificationsTable.insert {
            it[id] = notification.id
            it[userId] = notification.userId
            it[deviceId] = notification.deviceId
            it[type] = notification.type.name.lowercase()
            it[title] = notification.title
            it[body] = notification.body
            it[data] = notification.data?.let { d ->
                Json.encodeToString(kotlinx.serialization.serializer(), d)
            }
            it[channel] = notification.channel.name.lowercase()
            it[fcmMessageId] = notification.fcmMessageId
            it[status] = notification.status.name.lowercase()
            it[sentAt] = notification.sentAt
            it[deliveredAt] = notification.deliveredAt
            it[clickedAt] = notification.clickedAt
            it[createdAt] = notification.createdAt
            it[deliverAfter] = notification.deliverAfter
            it[attempts] = notification.attempts
            it[lastError] = notification.lastError
        }
        notification
    }

    override suspend fun findByUserId(userId: UUID, limit: Int): List<Notification> = dbQuery {
        NotificationsTable.selectAll().where { NotificationsTable.userId eq userId }
            .orderBy(NotificationsTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toNotification() }
    }

    override suspend fun findById(id: UUID): Notification? = dbQuery {
        NotificationsTable.selectAll().where { NotificationsTable.id eq id }
            .singleOrNull()?.toNotification()
    }

    override suspend fun findByUserAndType(userId: UUID, type: NotificationType): List<Notification> =
        dbQuery {
            NotificationsTable.selectAll().where {
                (NotificationsTable.userId eq userId) and
                    (NotificationsTable.type eq type.name.lowercase())
            }.map { it.toNotification() }
        }

    override suspend fun update(notification: Notification): Notification = dbQuery {
        NotificationsTable.update({ NotificationsTable.id eq notification.id }) {
            it[status] = notification.status.name.lowercase()
            it[sentAt] = notification.sentAt
            it[deliveredAt] = notification.deliveredAt
            it[clickedAt] = notification.clickedAt
            it[fcmMessageId] = notification.fcmMessageId
            it[deliverAfter] = notification.deliverAfter
            it[attempts] = notification.attempts
            it[lastError] = notification.lastError
        }
        notification
    }

    override suspend fun countByUserAndType(userId: UUID, type: NotificationType): Int = dbQuery {
        NotificationsTable.selectAll().where {
            (NotificationsTable.userId eq userId) and
                (NotificationsTable.type eq type.name.lowercase())
        }.count().toInt()
    }

    override suspend fun findPending(): List<Notification> = dbQuery {
        NotificationsTable.selectAll().where { NotificationsTable.status eq NotificationStatus.PENDING.name.lowercase() }
            .orderBy(NotificationsTable.createdAt, SortOrder.ASC)
            .map { it.toNotification() }
    }

    override suspend fun claimDue(now: Instant, lease: Duration, limit: Int): List<Notification> = dbQuery {
        val t = NotificationsTable
        // Rows locked by a concurrent claimer are skipped, so two callers never get the same notification.
        val ids = t.select(t.id)
            .where { (t.status eq NotificationStatus.PENDING.name.lowercase()) and (t.deliverAfter.isNull() or (t.deliverAfter lessEq now)) }
            .orderBy(t.createdAt, SortOrder.ASC)
            .limit(limit)
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            .map { it[t.id] }
        if (ids.isEmpty()) return@dbQuery emptyList()
        t.update({ t.id inList ids }) { it[deliverAfter] = now.plus(lease) }
        t.selectAll().where { t.id inList ids }.orderBy(t.createdAt, SortOrder.ASC).map { it.toNotification() }
    }

    private fun ResultRow.toNotification() = Notification(
        id = this[NotificationsTable.id],
        userId = this[NotificationsTable.userId],
        deviceId = this[NotificationsTable.deviceId],
        type = NotificationType.valueOf(this[NotificationsTable.type].uppercase()),
        title = this[NotificationsTable.title],
        body = this[NotificationsTable.body],
        data = this[NotificationsTable.data]?.let { Json.decodeFromString<Map<String, String>>(it) },
        channel = NotificationChannel.valueOf(this[NotificationsTable.channel].uppercase()),
        fcmMessageId = this[NotificationsTable.fcmMessageId],
        status = NotificationStatus.valueOf(this[NotificationsTable.status].uppercase()),
        sentAt = this[NotificationsTable.sentAt],
        deliveredAt = this[NotificationsTable.deliveredAt],
        clickedAt = this[NotificationsTable.clickedAt],
        createdAt = this[NotificationsTable.createdAt],
        deliverAfter = this[NotificationsTable.deliverAfter],
        attempts = this[NotificationsTable.attempts],
        lastError = this[NotificationsTable.lastError]
    )
}
