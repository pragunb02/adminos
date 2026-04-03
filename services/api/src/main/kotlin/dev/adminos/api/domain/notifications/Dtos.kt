package dev.adminos.api.domain.notifications

import kotlinx.serialization.Serializable

// ── Request DTOs ──

@Serializable
data class UpdatePreferencesRequest(
    val weeklyBriefing: Boolean? = null,
    val briefingDay: String? = null,
    val briefingTime: String? = null,
    val billReminders: Boolean? = null,
    val billReminderDays: List<Int>? = null,
    val anomalyAlerts: Boolean? = null,
    val subscriptionFlags: Boolean? = null,
    val emailNotifications: Boolean? = null,
    val pushNotifications: Boolean? = null,
    val quietHoursStart: String? = null,
    val quietHoursEnd: String? = null
)

// ── Response DTOs ──

@Serializable
data class NotificationPreferencesResponse(
    val id: String,
    val userId: String,
    val weeklyBriefing: Boolean,
    val briefingDay: String,
    val briefingTime: String,
    val billReminders: Boolean,
    val billReminderDays: List<Int>,
    val anomalyAlerts: Boolean,
    val subscriptionFlags: Boolean,
    val emailNotifications: Boolean,
    val pushNotifications: Boolean,
    val quietHoursStart: String,
    val quietHoursEnd: String,
    val createdAt: String,
    val updatedAt: String
) {
    companion object {
        fun from(p: NotificationPreferences) = NotificationPreferencesResponse(
            id = p.id.toString(),
            userId = p.userId.toString(),
            weeklyBriefing = p.weeklyBriefing,
            briefingDay = p.briefingDay.name.lowercase(),
            briefingTime = p.briefingTime.toString(),
            billReminders = p.billReminders,
            billReminderDays = p.billReminderDays,
            anomalyAlerts = p.anomalyAlerts,
            subscriptionFlags = p.subscriptionFlags,
            emailNotifications = p.emailNotifications,
            pushNotifications = p.pushNotifications,
            quietHoursStart = p.quietHoursStart.toString(),
            quietHoursEnd = p.quietHoursEnd.toString(),
            createdAt = p.createdAt.toString(),
            updatedAt = p.updatedAt.toString()
        )
    }
}

@Serializable
data class NotificationResponse(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    val channel: String,
    val status: String,
    val sentAt: String?,
    val createdAt: String
) {
    companion object {
        fun from(n: Notification) = NotificationResponse(
            id = n.id.toString(),
            type = n.type.name.lowercase(),
            title = n.title,
            body = n.body,
            channel = n.channel.name.lowercase(),
            status = n.status.name.lowercase(),
            sentAt = n.sentAt?.toString(),
            createdAt = n.createdAt.toString()
        )
    }
}
