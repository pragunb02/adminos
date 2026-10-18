package dev.adminos.api.domain.balances

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Balance readings (AOS-720). Implementations encrypt [BalanceReading.amount] at rest. */
interface BalanceRepository {
    /**
     * Saves [reading] unless the same input (user, source, source ref) was recorded before, then keeps only the
     * newest [BalanceRules.KEEP_PER_ACCOUNT] readings of its account.
     * @return false for a duplicate
     */
    suspend fun save(reading: BalanceReading): Boolean

    /** [userId]'s readings (at most [BalanceRules.KEEP_PER_ACCOUNT] per account), newest first. */
    suspend fun forUser(userId: UUID): List<BalanceReading>
}

class InMemoryBalanceRepository : BalanceRepository {
    private val rows = ConcurrentHashMap<UUID, BalanceReading>()
    private val lock = Mutex()

    override suspend fun save(reading: BalanceReading): Boolean = lock.withLock {
        if (rows.values.any { it.userId == reading.userId && it.source == reading.source && it.sourceRef == reading.sourceRef }) return@withLock false
        rows[reading.id] = reading
        rows.values.filter { it.userId == reading.userId && it.account == reading.account }
            .sortedWith(BalanceReading.NEWEST_FIRST).drop(BalanceRules.KEEP_PER_ACCOUNT).forEach { rows.remove(it.id) }
        true
    }

    override suspend fun forUser(userId: UUID): List<BalanceReading> =
        rows.values.filter { it.userId == userId }.sortedWith(BalanceReading.NEWEST_FIRST)
}
