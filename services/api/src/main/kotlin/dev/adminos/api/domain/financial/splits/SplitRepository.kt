package dev.adminos.api.domain.financial.splits

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Stored Splitwise imports and entries (V41). The Exposed implementation encrypts descriptions and names at rest. */
interface SplitRepository {
    suspend fun saveImport(import: SplitImport): SplitImport
    suspend fun findImport(id: UUID): SplitImport?
    suspend fun imports(userId: UUID): List<SplitImport>
    /** Deletes the import and its entries; the caller reverts their effects first. */
    suspend fun deleteImport(id: UUID)

    suspend fun save(entry: SplitEntry): SplitEntry
    suspend fun update(entry: SplitEntry): SplitEntry
    suspend fun findById(id: UUID): SplitEntry?
    /** The user's entries with these fingerprints, for idempotent re-imports. */
    suspend fun findBySourceRefs(userId: UUID, refs: Collection<String>): List<SplitEntry>
    /** Newest first; all states when [state] is null. */
    suspend fun findByUser(userId: UUID, state: SplitState? = null, limit: Int = 500): List<SplitEntry>
    suspend fun findByImport(importId: UUID): List<SplitEntry>
    /** Unmatched entries looking for this bank amount whose date is within [from]..[to] (for a new transaction). */
    suspend fun findUnmatched(userId: UUID, bankAmount: BigDecimal, from: LocalDate, to: LocalDate): List<SplitEntry>
    /** Transactions held by an applied or suggested entry of the user (a transaction serves one entry). */
    suspend fun heldTransactions(userId: UUID): Set<UUID>
    /** The owner's Splitwise balance per currency: the sum of their net change over every entry. */
    suspend fun balances(userId: UUID): Map<String, BigDecimal>
    suspend fun countByState(userId: UUID): Map<SplitState, Int>
}

class InMemorySplitRepository : SplitRepository {
    private val imports = ConcurrentHashMap<UUID, SplitImport>()
    private val entries = ConcurrentHashMap<UUID, SplitEntry>()

    override suspend fun saveImport(import: SplitImport) = import.also { imports[it.id] = it }
    override suspend fun findImport(id: UUID) = imports[id]
    override suspend fun imports(userId: UUID) = imports.values.filter { it.userId == userId }.sortedByDescending { it.createdAt }
    override suspend fun deleteImport(id: UUID) {
        entries.values.removeIf { it.importId == id }
        imports.remove(id)
    }

    override suspend fun save(entry: SplitEntry): SplitEntry {
        require(entries.values.none { it.userId == entry.userId && it.sourceRef == entry.sourceRef }) { "entry already imported" }
        checkOneEntryPerTransaction(entry)
        return entry.also { entries[it.id] = it }
    }

    override suspend fun update(entry: SplitEntry): SplitEntry {
        checkOneEntryPerTransaction(entry)
        return entry.copy(updatedAt = Instant.now()).also { entries[it.id] = it }
    }

    /** Mirrors the partial unique index on applied entries. */
    private fun checkOneEntryPerTransaction(entry: SplitEntry) {
        if (entry.state != SplitState.APPLIED) return
        check(entries.values.none { it.id != entry.id && it.state == SplitState.APPLIED && it.transactionId == entry.transactionId }) {
            "the transaction is already split"
        }
    }

    override suspend fun findById(id: UUID) = entries[id]
    override suspend fun findBySourceRefs(userId: UUID, refs: Collection<String>): List<SplitEntry> {
        val wanted = refs.toSet()
        return entries.values.filter { it.userId == userId && it.sourceRef in wanted }
    }
    override suspend fun findByUser(userId: UUID, state: SplitState?, limit: Int) =
        entries.values.filter { it.userId == userId && (state == null || it.state == state) }
            .sortedWith(compareByDescending<SplitEntry> { it.date }.thenByDescending { it.createdAt }.thenBy { it.id }).take(limit)
    override suspend fun findByImport(importId: UUID) = entries.values.filter { it.importId == importId }
    override suspend fun findUnmatched(userId: UUID, bankAmount: BigDecimal, from: LocalDate, to: LocalDate) =
        entries.values.filter {
            it.userId == userId && it.state == SplitState.UNMATCHED && !it.date.isBefore(from) && !it.date.isAfter(to) &&
                bankAmountOf(it).compareTo(bankAmount) == 0
        }.sortedWith(compareBy<SplitEntry> { it.date }.thenBy { it.id })
    override suspend fun heldTransactions(userId: UUID) =
        entries.values.filter { it.userId == userId && (it.state == SplitState.APPLIED || it.state == SplitState.SUGGESTED) }
            .mapNotNull { it.transactionId }.toSet()
    override suspend fun balances(userId: UUID) =
        entries.values.filter { it.userId == userId }.groupBy { it.currency }
            .mapValues { (_, es) -> es.fold(BigDecimal.ZERO.setScale(2)) { acc, e -> acc + e.ownerNet } }
    override suspend fun countByState(userId: UUID) =
        entries.values.filter { it.userId == userId }.groupingBy { it.state }.eachCount()
}

/** The bank amount an entry looks for: the whole bill it was paid with, or the settle-up amount. */
fun bankAmountOf(e: SplitEntry): BigDecimal = if (e.effect == SplitEffect.ADJUST_DEBIT) e.cost else e.ownerNet.abs()
