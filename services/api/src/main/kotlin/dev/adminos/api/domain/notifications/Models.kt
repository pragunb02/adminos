package dev.adminos.api.domain.notifications

import java.time.Instant
import java.time.LocalTime
import java.util.UUID

// ── Enums ──

enum class NotificationType { BILL_REMINDER, ANOMALY, BRIEFING, SUBSCRIPTION_FLAG, NUDGE, OVERDUE, GENERAL }
enum class NotificationChannel { PUSH, EMAIL, IN_APP }
enum class NotificationStatus { PENDING, SENT, DELIVERED, CLICKED, FAILED }
enum class Weekday { MON, TUE, WED, THU, FRI, SAT, SUN }

// ── Domain Models ──

data class NotificationPreferences(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val weeklyBriefing: Boolean = true,
    val briefingDay: Weekday = Weekday.MON,
    val briefingTime: LocalTime = LocalTime.of(8, 0),
    val billReminders: Boolean = true,
    val billReminderDays: List<Int> = listOf(3, 1),
    val anomalyAlerts: Boolean = true,
    val subscriptionFlags: Boolean = true,
    val emailNotifications: Boolean = true,
    val pushNotifications: Boolean = true,
    val quietHoursStart: LocalTime = LocalTime.of(22, 0),
    val quietHoursEnd: LocalTime = LocalTime.of(8, 0),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    companion object {
        fun defaultFor(userId: UUID) = NotificationPreferences(userId = userId)
    }
}

data class Notification(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val deviceId: UUID? = null,
    val type: NotificationType,
    val title: String,
    val body: String,
    val data: Map<String, String>? = null,
    val channel: NotificationChannel,
    val fcmMessageId: String? = null,
    val status: NotificationStatus = NotificationStatus.PENDING,
    val sentAt: Instant? = null,
    val deliveredAt: Instant? = null,
    val clickedAt: Instant? = null,
    val createdAt: Instant = Instant.now()
)
