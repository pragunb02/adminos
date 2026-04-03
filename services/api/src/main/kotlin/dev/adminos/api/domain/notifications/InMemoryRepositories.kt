package dev.adminos.api.domain.notifications

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ── In-Memory Notification Preferences Repository ──

class InMemoryNotificationPreferencesRepository : NotificationPreferencesRepository {
    private val store = ConcurrentHashMap<UUID, NotificationPreferences>()

    override suspend fun findByUserId(userId: UUID): NotificationPreferences? =
        store.values.find { it.userId == userId }

    override suspend fun save(preferences: NotificationPreferences): NotificationPreferences {
        store[preferences.id] = preferences
        return preferences
    }

    override suspend fun update(preferences: NotificationPreferences): NotificationPreferences {
        store[preferences.id] = preferences.copy(updatedAt = Instant.now())
        return store[preferences.id]!!
    }
}

// ── In-Memory Notification Repository ──

class InMemoryNotificationRepository : NotificationRepository {
    private val store = ConcurrentHashMap<UUID, Notification>()

    override suspend fun save(notification: Notification): Notification {
        store[notification.id] = notification
        return notification
    }

    override suspend fun findByUserId(userId: UUID, limit: Int): List<Notification> =
        store.values
            .filter { it.userId == userId }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override suspend fun findById(id: UUID): Notification? = store[id]

    override suspend fun update(notification: Notification): Notification {
        store[notification.id] = notification
        return notification
    }

    override suspend fun findByUserAndType(userId: UUID, type: NotificationType): List<Notification> =
        store.values.filter { it.userId == userId && it.type == type }

    override suspend fun countByUserAndType(userId: UUID, type: NotificationType): Int =
        store.values.count { it.userId == userId && it.type == type }

    override suspend fun findPending(): List<Notification> =
        store.values.filter { it.status == NotificationStatus.PENDING }
            .sortedBy { it.createdAt }
}

// ── In-Memory Device Repository ──

class InMemoryDeviceRepository : DeviceRepository {
    private val store = ConcurrentHashMap<UUID, Device>()

    override suspend fun findByUserId(userId: UUID): List<Device> =
        store.values.filter { it.userId == userId }

    override suspend fun findById(id: UUID): Device? = store[id]

    override suspend fun save(device: Device): Device {
        store[device.id] = device
        return device
    }

    override suspend fun update(device: Device): Device {
        store[device.id] = device.copy(updatedAt = Instant.now())
        return store[device.id]!!
    }

    override suspend fun removeToken(deviceId: UUID) {
        store.computeIfPresent(deviceId) { _, d ->
            d.copy(fcmToken = null, updatedAt = Instant.now())
        }
    }
}
