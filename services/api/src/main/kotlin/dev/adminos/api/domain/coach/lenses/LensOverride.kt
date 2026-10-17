package dev.adminos.api.domain.coach.lenses

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The owner's choice for one merchant in one lens (AOS-901): [include] = true adds it (it then counts,
 * even a person-to-person payee), false takes it out (it never counts in that lens).
 * [merchantKey] is the merchant name as the console shows it, trimmed and lower-cased.
 */
data class LensOverride(
    val userId: UUID,
    val lensId: LensId,
    val merchantKey: String,
    /** The name as the owner typed or saw it, for display. */
    val merchant: String,
    val include: Boolean,
    val updatedAt: Instant
) {
    init {
        require(merchantKey == keyOf(merchant)) { "merchantKey must be the normalised merchant name" }
        require(merchantKey.isNotEmpty() && merchantKey.length <= MAX_MERCHANT) { "A merchant is 1..$MAX_MERCHANT characters" }
    }

    companion object {
        const val MAX_MERCHANT = 255

        /** How merchants are compared everywhere in the coach: trimmed, lower case. */
        fun keyOf(merchant: String): String = merchant.trim().lowercase()

        fun of(userId: UUID, lensId: LensId, merchant: String, include: Boolean, at: Instant) =
            LensOverride(userId, lensId, keyOf(merchant), merchant.trim(), include, at)
    }
}

/** All of one user's overrides, indexed for matching. */
data class LensOverrides(val all: List<LensOverride>) {
    private val byKey: Map<Pair<LensId, String>, Boolean> = all.associate { (it.lensId to it.merchantKey) to it.include }

    /** true = added, false = removed, null = no override (the lens rules decide). */
    fun choiceFor(lens: LensId, merchant: String?): Boolean? = merchant?.let { byKey[lens to LensOverride.keyOf(it)] }

    companion object {
        val NONE = LensOverrides(emptyList())
    }
}

interface LensOverrideRepository {
    /** Inserts, or replaces the choice for the same (user, lens, merchant). */
    suspend fun upsert(override: LensOverride): LensOverride
    suspend fun findByUser(userId: UUID): List<LensOverride>
    /** @return whether there was one */
    suspend fun delete(userId: UUID, lensId: LensId, merchantKey: String): Boolean
}

class InMemoryLensOverrideRepository : LensOverrideRepository {
    private val store = ConcurrentHashMap<Triple<UUID, LensId, String>, LensOverride>()

    override suspend fun upsert(override: LensOverride): LensOverride {
        store[Triple(override.userId, override.lensId, override.merchantKey)] = override
        return override
    }

    override suspend fun findByUser(userId: UUID): List<LensOverride> =
        store.values.filter { it.userId == userId }.sortedWith(compareBy({ it.lensId.value }, { it.merchantKey }))

    override suspend fun delete(userId: UUID, lensId: LensId, merchantKey: String): Boolean =
        store.remove(Triple(userId, lensId, merchantKey)) != null
}
