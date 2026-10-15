package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.notifications.*
import dev.adminos.api.infrastructure.database.tables.NotificationsTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import dev.adminos.api.infrastructure.database.dbQuery
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
        createdAt = this[NotificationsTable.createdAt]
    )
}
