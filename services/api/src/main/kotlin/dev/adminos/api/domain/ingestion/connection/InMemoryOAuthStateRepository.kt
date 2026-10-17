package dev.adminos.api.domain.ingestion.connection

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryOAuthStateRepository : OAuthStateRepository {
    private val states = ConcurrentHashMap<UUID, OAuthState>()

    override suspend fun save(state: OAuthState): OAuthState {
        states[state.id] = state
        return state
    }

    override suspend fun findByHash(stateHash: String): OAuthState? = states.values.find { it.stateHash == stateHash }

    override suspend fun markUsed(id: UUID, at: Instant): Boolean {
        var marked = false
        states.computeIfPresent(id) { _, s -> if (s.usedAt == null) s.copy(usedAt = at).also { marked = true } else s }
        return marked
    }

    override suspend fun deleteExpiredBefore(before: Instant): Int {
        val old = states.values.filter { it.expiresAt < before }
        old.forEach { states.remove(it.id) }
        return old.size
    }
}
