package dev.adminos.api.domain.financial.grouping

import dev.adminos.api.domain.financial.MerchantKey
import java.time.Instant
import java.util.UUID

/** A confirmed merge (AOS-709, V46): payments under [aliasKey] are this user's [canonicalName]. */
data class MerchantAlias(
    val userId: UUID,
    val aliasKey: String,
    val canonicalKey: String,
    val canonicalName: String,
    val createdAt: Instant = Instant.now()
) {
    init {
        require(aliasKey.isNotBlank() && canonicalKey.isNotBlank() && canonicalName.isNotBlank()) { "An alias names both merchants" }
        require(aliasKey != canonicalKey) { "A merchant isn't its own alias" }
        require(canonicalKey == MerchantKey.of(canonicalName)) { "The canonical key is the canonical name's key" }
    }
}

/** Two merchant keys the user said are different: never proposed together again. Stored in sorted order. */
data class RejectedPair(val first: String, val second: String) {
    init {
        require(first < second) { "A rejected pair is stored in sorted order, without repeats" }
    }

    companion object {
        fun of(a: String, b: String): RejectedPair = if (a < b) RejectedPair(a, b) else RejectedPair(b, a)
    }
}

interface MerchantAliasRepository {
    suspend fun aliases(userId: UUID): List<MerchantAlias>
    suspend fun rejections(userId: UUID): Set<RejectedPair>

    /**
     * Makes every key in [members] an alias of ([canonicalKey], [canonicalName]), and moves aliases that
     * pointed at a member to the canonical one too, so aliases never chain. Atomic.
     */
    suspend fun merge(userId: UUID, canonicalKey: String, canonicalName: String, members: Set<String>, at: Instant)

    suspend fun reject(userId: UUID, pairs: Set<RejectedPair>, at: Instant)
}

/**
 * Reads names through the user's aliases: the one place that says which merchant a payment belongs
 * to, for subscription detection and the analytics (AOS-709).
 */
class AliasResolver(aliases: List<MerchantAlias>) {
    private val byKey: Map<String, MerchantAlias> = aliases.associateBy { it.aliasKey }

    /** The key to group [merchantName] under, or null when it has no name. */
    fun key(merchantName: String?): String? = MerchantKey.of(merchantName)?.let { byKey[it]?.canonicalKey ?: it }

    /** The name to show for [merchantName]: the merge's name if it was merged, else itself (trimmed). */
    fun name(merchantName: String?): String? {
        val key = MerchantKey.of(merchantName) ?: return null
        return byKey[key]?.canonicalName ?: merchantName?.trim()
    }

    /** The name a merge gave [canonicalKey], or null when nothing was merged into it. */
    fun canonicalNameOf(canonicalKey: String): String? = byKey.values.firstOrNull { it.canonicalKey == canonicalKey }?.canonicalName

    /** The keys merged into [canonicalKey] (not including it). */
    fun membersOf(canonicalKey: String): Set<String> = byKey.values.filter { it.canonicalKey == canonicalKey }.map { it.aliasKey }.toSet()

    val isEmpty: Boolean get() = byKey.isEmpty()

    companion object {
        val NONE = AliasResolver(emptyList())
    }
}

class InMemoryMerchantAliasRepository : MerchantAliasRepository {
    private val aliases = mutableMapOf<Pair<UUID, String>, MerchantAlias>()
    private val rejected = mutableSetOf<Pair<UUID, RejectedPair>>()

    override suspend fun aliases(userId: UUID): List<MerchantAlias> = synchronized(this) { aliases.values.filter { it.userId == userId } }

    override suspend fun rejections(userId: UUID): Set<RejectedPair> = synchronized(this) { rejected.filter { it.first == userId }.map { it.second }.toSet() }

    override suspend fun merge(userId: UUID, canonicalKey: String, canonicalName: String, members: Set<String>, at: Instant) = synchronized(this) {
        val moved = aliases.values.filter { it.userId == userId && it.canonicalKey in members }.map { it.aliasKey }
        (members + moved).filter { it != canonicalKey }.forEach { key ->
            aliases[userId to key] = MerchantAlias(userId, key, canonicalKey, canonicalName, aliases[userId to key]?.createdAt ?: at)
        }
        aliases.remove(userId to canonicalKey) // the canonical merchant is nobody's alias
        Unit
    }

    override suspend fun reject(userId: UUID, pairs: Set<RejectedPair>, at: Instant) = synchronized(this) {
        pairs.forEach { rejected += userId to it }
    }
}
