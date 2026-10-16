package dev.adminos.api.domain.identity

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What a device token may do. Least privilege: a token gets only what its device needs. */
object Scopes {
    const val INGEST_SMS = "ingest:sms"
    val ALL = setOf(INGEST_SMS)
}

data class DeviceToken(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val name: String,
    val scopes: Set<String>,
    val createdAt: Instant = Instant.now(),
    val expiresAt: Instant,
    val lastUsedAt: Instant? = null,
    val revokedAt: Instant? = null
) {
    init {
        require(name.isNotBlank() && name.length <= 100) { "name must be 1..100 characters" }
        require(scopes.isNotEmpty() && scopes.all { it in Scopes.ALL }) { "unknown scope in $scopes" }
    }
    fun isActive(now: Instant) = revokedAt == null && expiresAt.isAfter(now)
}

interface DeviceTokenRepository {
    suspend fun save(token: DeviceToken): DeviceToken
    suspend fun findById(id: UUID): DeviceToken?
    suspend fun findByUser(userId: UUID): List<DeviceToken>
    suspend fun revoke(id: UUID, at: Instant)
    suspend fun touch(id: UUID, at: Instant)
}

/** A device token as issued: the secret [token] is only ever returned here, at creation. */
data class IssuedDeviceToken(val record: DeviceToken, val token: String)

/** The authenticated device behind a request. */
data class DevicePrincipal(val userId: UUID, val tokenId: UUID, val scopes: Set<String>)

class DeviceTokenService(
    private val repository: DeviceTokenRepository,
    private val jwtService: JwtService,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofDays(365)
) {
    suspend fun issue(userId: UUID, name: String, scopes: Set<String>): IssuedDeviceToken {
        val now = clock.instant()
        val record = repository.save(DeviceToken(userId = userId, name = name.trim(), scopes = scopes, createdAt = now, expiresAt = now.plus(ttl)))
        return IssuedDeviceToken(record, jwtService.generateDeviceToken(userId, record.id, scopes, ttl))
    }

    suspend fun list(userId: UUID): List<DeviceToken> = repository.findByUser(userId)

    /** Revokes one of the user's own tokens. @return false if it isn't theirs or doesn't exist */
    suspend fun revoke(userId: UUID, tokenId: UUID): Boolean {
        val t = repository.findById(tokenId)?.takeIf { it.userId == userId } ?: return false
        if (t.revokedAt == null) repository.revoke(t.id, clock.instant())
        return true
    }

    /**
     * Verifies the JWT, then that its row exists, belongs to the subject, isn't revoked/expired and
     * grants [requiredScope]. Any failure → null (the caller answers 401/403 without detail).
     */
    suspend fun authenticate(token: String, requiredScope: String): DevicePrincipal? {
        val claims = jwtService.verifyDeviceToken(token) ?: return null
        val record = repository.findById(claims.tokenId) ?: return null
        val now = clock.instant()
        if (record.userId != claims.userId || !record.isActive(now) || requiredScope !in record.scopes) return null
        repository.touch(record.id, now)
        return DevicePrincipal(record.userId, record.id, record.scopes)
    }
}

class InMemoryDeviceTokenRepository : DeviceTokenRepository {
    private val store = java.util.concurrent.ConcurrentHashMap<UUID, DeviceToken>()
    override suspend fun save(token: DeviceToken) = token.also { store[it.id] = it }
    override suspend fun findById(id: UUID) = store[id]
    override suspend fun findByUser(userId: UUID) = store.values.filter { it.userId == userId }.sortedByDescending { it.createdAt }
    override suspend fun revoke(id: UUID, at: Instant) { store.computeIfPresent(id) { _, t -> t.copy(revokedAt = at) } }
    override suspend fun touch(id: UUID, at: Instant) { store.computeIfPresent(id) { _, t -> t.copy(lastUsedAt = at) } }
}
