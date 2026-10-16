package dev.adminos.api.domain.notifications.push

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryPushSubscriptionRepository : PushSubscriptionRepository {
    private val byEndpoint = ConcurrentHashMap<PushEndpoint, PushSubscription>()

    override suspend fun upsert(subscription: PushSubscription): PushSubscription = synchronized(this) {
        // Same endpoint = same device: keep the row's id, take the new owner, keys and label.
        val existing = byEndpoint[subscription.endpoint]
        val saved = existing?.let { subscription.copy(id = it.id) } ?: subscription
        byEndpoint[subscription.endpoint] = saved
        saved
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
