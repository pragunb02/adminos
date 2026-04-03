package dev.adminos.api.domain.notifications

import java.util.UUID

// ── Notification Preferences Repository ──

interface NotificationPreferencesRepository {
    suspend fun findByUserId(userId: UUID): NotificationPreferences?
    suspend fun save(preferences: NotificationPreferences): NotificationPreferences
    suspend fun update(preferences: NotificationPreferences): NotificationPreferences
}

// ── Notification Repository ──

interface NotificationRepository {
    suspend fun save(notification: Notification): Notification
    suspend fun findByUserId(userId: UUID, limit: Int = 50): List<Notification>
    suspend fun findById(id: UUID): Notification?
    suspend fun findByUserAndType(userId: UUID, type: NotificationType): List<Notification>
    suspend fun update(notification: Notification): Notification
    suspend fun countByUserAndType(userId: UUID, type: NotificationType): Int
    suspend fun findPending(): List<Notification>
}

// ── Device Repository (for FCM token lookup) ──

data class Device(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val deviceType: String = "android",
    val deviceName: String? = null,
    val fcmToken: String? = null,
    val appVersion: String? = null,
    val osVersion: String? = null,
    val smsPermission: Boolean = false,
    val createdAt: java.time.Instant = java.time.Instant.now(),
    val updatedAt: java.time.Instant = java.time.Instant.now()
)

interface DeviceRepository {
    suspend fun findByUserId(userId: UUID): List<Device>
    suspend fun findById(id: UUID): Device?
    suspend fun save(device: Device): Device
    suspend fun update(device: Device): Device
    suspend fun removeToken(deviceId: UUID)
}
