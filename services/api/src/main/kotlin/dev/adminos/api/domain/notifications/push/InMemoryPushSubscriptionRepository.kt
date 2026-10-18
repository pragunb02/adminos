package dev.adminos.api.domain.notifications.push

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryPushSubscriptionRepository : PushSubscriptionRepository {
    private val byEndpoint = ConcurrentHashMap<PushEndpoint, PushSubscription>()

    override suspend fun save(subscription: PushSubscription): PushSaveResult = synchronized(this) {
        // Same endpoint = same device: keep the row's id and creation time, take the new keys and label.
        val existing = byEndpoint[subscription.endpoint]
        if (existing != null && existing.userId != subscription.userId) return PushSaveResult.OwnedByAnotherUser
        val saved = existing?.let { subscription.copy(id = it.id, createdAt = it.createdAt) } ?: subscription
        byEndpoint[subscription.endpoint] = saved
        PushSaveResult.Saved(saved)
    }

    override suspend fun findByUserId(userId: UUID) = byEndpoint.values.filter { it.userId == userId }.sortedBy { it.createdAt }

    override suspend fun delete(userId: UUID, endpoint: PushEndpoint): Boolean = synchronized(this) {
        val existing = byEndpoint[endpoint]?.takeIf { it.userId == userId } ?: return false
        byEndpoint.remove(existing.endpoint)
        true
    }

    override suspend fun deleteById(id: UUID) {
        byEndpoint.values.firstOrNull { it.id == id }?.let { byEndpoint.remove(it.endpoint) }
    }

    override suspend fun markSucceeded(id: UUID, at: Instant) {
        byEndpoint.values.firstOrNull { it.id == id }?.let { byEndpoint[it.endpoint] = it.copy(lastSuccessAt = at) }
    }
}
