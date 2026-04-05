package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.notifications.*
import dev.adminos.api.infrastructure.database.tables.NotificationPreferencesTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.util.UUID

class ExposedNotificationPreferencesRepository : NotificationPreferencesRepository {

    override suspend fun findByUserId(userId: UUID): NotificationPreferences? = newSuspendedTransaction {
        NotificationPreferencesTable.select { NotificationPreferencesTable.userId eq userId }
            .singleOrNull()?.toPreferences()
    }

    override suspend fun save(preferences: NotificationPreferences): NotificationPreferences =
        newSuspendedTransaction {
            NotificationPreferencesTable.insert {
                it[id] = preferences.id
                it[userId] = preferences.userId
                it[weeklyBriefing] = preferences.weeklyBriefing
                it[briefingDay] = preferences.briefingDay.name.lowercase()
                it[briefingTime] = preferences.briefingTime
                it[billReminders] = preferences.billReminders
                it[billReminderDays] = preferences.billReminderDays.joinToString(",")
                it[anomalyAlerts] = preferences.anomalyAlerts
                it[subscriptionFlags] = preferences.subscriptionFlags
                it[emailNotifications] = preferences.emailNotifications
                it[pushNotifications] = preferences.pushNotifications
                it[quietHoursStart] = preferences.quietHoursStart
                it[quietHoursEnd] = preferences.quietHoursEnd
                it[createdAt] = preferences.createdAt
                it[updatedAt] = preferences.updatedAt
            }
            preferences
        }

    override suspend fun update(preferences: NotificationPreferences): NotificationPreferences =
        newSuspendedTransaction {
            val now = Instant.now()
            NotificationPreferencesTable.update({
                NotificationPreferencesTable.id eq preferences.id
            }) {
                it[weeklyBriefing] = preferences.weeklyBriefing
                it[briefingDay] = preferences.briefingDay.name.lowercase()
                it[briefingTime] = preferences.briefingTime
                it[billReminders] = preferences.billReminders
                it[billReminderDays] = preferences.billReminderDays.joinToString(",")
                it[anomalyAlerts] = preferences.anomalyAlerts
                it[subscriptionFlags] = preferences.subscriptionFlags
                it[emailNotifications] = preferences.emailNotifications
                it[pushNotifications] = preferences.pushNotifications
                it[quietHoursStart] = preferences.quietHoursStart
                it[quietHoursEnd] = preferences.quietHoursEnd
                it[updatedAt] = now
            }
            preferences.copy(updatedAt = now)
        }

    private fun ResultRow.toPreferences() = NotificationPreferences(
        id = this[NotificationPreferencesTable.id],
        userId = this[NotificationPreferencesTable.userId],
        weeklyBriefing = this[NotificationPreferencesTable.weeklyBriefing],
        briefingDay = Weekday.valueOf(this[NotificationPreferencesTable.briefingDay].uppercase()),
        briefingTime = this[NotificationPreferencesTable.briefingTime],
        billReminders = this[NotificationPreferencesTable.billReminders],
        billReminderDays = this[NotificationPreferencesTable.billReminderDays]
            ?.split(",")?.filter { it.isNotBlank() }?.map { it.trim().toInt() }
            ?: listOf(3, 1),
        anomalyAlerts = this[NotificationPreferencesTable.anomalyAlerts],
        subscriptionFlags = this[NotificationPreferencesTable.subscriptionFlags],
        emailNotifications = this[NotificationPreferencesTable.emailNotifications],
        pushNotifications = this[NotificationPreferencesTable.pushNotifications],
        quietHoursStart = this[NotificationPreferencesTable.quietHoursStart],
        quietHoursEnd = this[NotificationPreferencesTable.quietHoursEnd],
        createdAt = this[NotificationPreferencesTable.createdAt],
        updatedAt = this[NotificationPreferencesTable.updatedAt]
    )
}
