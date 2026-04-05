package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.time
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed table objects for the Notifications domain.
 * Maps 1:1 to infra/migrations/006_create_notification_tables.sql
 */

object NotificationsTable : Table("notifications") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val deviceId = uuid("device_id").references(DevicesTable.id).nullable()
    val type = varchar("type", 30)
    val title = varchar("title", 255)
    val body = text("body")
    val data = text("data").nullable() // JSONB
    val channel = varchar("channel", 20)
    val fcmMessageId = varchar("fcm_message_id", 255).nullable()
    val status = varchar("status", 20).default("pending")
    val sentAt = timestamp("sent_at").nullable()
    val deliveredAt = timestamp("delivered_at").nullable()
    val clickedAt = timestamp("clicked_at").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object NotificationPreferencesTable : Table("notification_preferences") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val weeklyBriefing = bool("weekly_briefing").default(true)
    val briefingDay = varchar("briefing_day", 10).default("mon")
    val briefingTime = time("briefing_time")
    val billReminders = bool("bill_reminders").default(true)
    val billReminderDays = text("bill_reminder_days").nullable() // INTEGER[] stored as text
    val anomalyAlerts = bool("anomaly_alerts").default(true)
    val subscriptionFlags = bool("subscription_flags").default(true)
    val emailNotifications = bool("email_notifications").default(true)
    val pushNotifications = bool("push_notifications").default(true)
    val quietHoursStart = time("quiet_hours_start")
    val quietHoursEnd = time("quiet_hours_end")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}
