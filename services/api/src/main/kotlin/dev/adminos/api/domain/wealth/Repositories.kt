package dev.adminos.api.domain.wealth

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Investment accounts, looked up by their identifier's blind index (never by the identifier itself). */
interface InvestmentAccountRepository {
    /** The user's account of this kind with this blind index, or [account] saved as new. Idempotent. */
    suspend fun findOrCreate(account: InvestmentAccount): InvestmentAccount
    /** Only the user's own account: another user's id is not found. */
    suspend fun findById(userId: UUID, id: UUID): InvestmentAccount?
    suspend fun findByUser(userId: UUID): List<InvestmentAccount>
}

/** Instruments are public reference data, shared (D-79): matched by ISIN first, then AMFI code, then ticker + exchange ([sameInstrument]). */
interface InstrumentRepository {
    /** The existing instrument with the same ISIN / AMFI code / ticker, or [instrument] saved as new. */
    suspend fun findOrCreate(instrument: Instrument): Instrument
    suspend fun findByIds(ids: Collection<UUID>): List<Instrument>
    /** Indian mutual funds: an AMFI scheme code or an `INF…` ISIN (the NAV job prices exactly these, AOS-810). */
    suspend fun mutualFunds(): List<Instrument>
    suspend fun findById(id: UUID): Instrument?
}

/** What [InvestmentLedger.record] did. */
data class RecordResult(val batch: ImportBatch, val added: Int, val duplicates: Int)

/**
 * The append-only ledger (D-79). Rows are only ever inserted; a batch can be undone, which hides its rows
 * from [active] without deleting them (the database refuses updates to ledger rows).
 */
interface InvestmentLedger {
    /**
     * Records [batch] and those of [entries] whose source ref isn't already in an applied batch of the user,
     * atomically and serialised per user, so re-importing a file (or two imports at once) adds no duplicates.
     * The batch is saved with the real added/duplicate counts.
     */
    suspend fun record(batch: ImportBatch, entries: List<InvestmentTransaction>): RecordResult
    /** Every row of the user's applied batches (and rows without a batch), oldest first. */
    suspend fun active(userId: UUID): List<InvestmentTransaction>
    suspend fun findBatch(userId: UUID, id: UUID): ImportBatch?
    suspend fun batches(userId: UUID, limit: Int): List<ImportBatch>
    /** Undoes an applied batch. @return false if it isn't the user's or was already undone */
    suspend fun undo(userId: UUID, batchId: UUID, at: Instant): Boolean
}

class InMemoryInvestmentAccountRepository : InvestmentAccountRepository {
    private val store = ConcurrentHashMap<UUID, InvestmentAccount>()

    override suspend fun findOrCreate(account: InvestmentAccount): InvestmentAccount = put(account)

    @Synchronized
    private fun put(account: InvestmentAccount): InvestmentAccount =
        store.values.firstOrNull { it.userId == account.userId && it.kind == account.kind && it.identifier.blindIndex == account.identifier.blindIndex }
            ?: account.also { store[it.id] = it }

    override suspend fun findById(userId: UUID, id: UUID) = store[id]?.takeIf { it.userId == userId }
    override suspend fun findByUser(userId: UUID) = store.values.filter { it.userId == userId }.sortedBy { it.createdAt }
}

class InMemoryInstrumentRepository : InstrumentRepository {
    private val store = ConcurrentHashMap<UUID, Instrument>()

    override suspend fun findOrCreate(instrument: Instrument): Instrument = put(instrument)

    @Synchronized
    private fun put(instrument: Instrument): Instrument = store.values.sortedWith(compareBy<Instrument>({ it.createdAt }, { it.isin })).firstOrNull { sameInstrument(it, instrument) }
        ?: instrument.also { store[it.id] = it }

    override suspend fun findByIds(ids: Collection<UUID>) = ids.toSet().mapNotNull { store[it] }
    override suspend fun mutualFunds() = store.values.filter { isMutualFund(it) }.sortedBy { it.createdAt }
    override suspend fun findById(id: UUID) = store[id]
}

/** An Indian mutual fund scheme: it has an AMFI code or an `INF…` ISIN. */
internal fun isMutualFund(i: Instrument): Boolean = i.amfiCode != null || i.isin?.startsWith("INF") == true

/**
 * The matching rule both repositories use, keyed on the incoming [b]: its ISIN if it has one, else its AMFI code
 * (an AMFI code alone can't tell an IDCW payout from its reinvestment twin, so it is only a fallback), else its
 * exchange + ticker.
 */
internal fun sameInstrument(existing: Instrument, b: Instrument): Boolean = when {
    b.isin != null -> existing.isin == b.isin
    b.amfiCode != null -> existing.amfiCode == b.amfiCode
    else -> existing.ticker == b.ticker && existing.exchange == b.exchange
}

class InMemoryInvestmentLedger : InvestmentLedger {
    private val rows = mutableListOf<InvestmentTransaction>()
    private val batchStore = ConcurrentHashMap<UUID, ImportBatch>()

    override suspend fun record(batch: ImportBatch, entries: List<InvestmentTransaction>): RecordResult = recordNow(batch, entries)

    @Synchronized
    private fun recordNow(batch: ImportBatch, entries: List<InvestmentTransaction>): RecordResult {
        require(entries.all { it.userId == batch.userId && it.batchId == batch.id }) { "every entry belongs to the batch and its user" }
        val known = activeRows(batch.userId).map { it.sourceRef }.toMutableSet()
        val fresh = entries.filter { known.add(it.sourceRef) }
        val saved = batch.copy(added = fresh.size, duplicates = entries.size - fresh.size)
        batchStore[saved.id] = saved
        rows += fresh
        return RecordResult(saved, fresh.size, entries.size - fresh.size)
    }

    override suspend fun active(userId: UUID) = sortedActive(userId)

    @Synchronized
    private fun sortedActive(userId: UUID) = activeRows(userId).sortedWith(compareBy({ it.tradeDate }, { it.createdAt }))

    private fun activeRows(userId: UUID) = rows.filter { r ->
        r.userId == userId && (r.batchId == null || batchStore[r.batchId]?.status == ImportStatus.APPLIED)
    }

    override suspend fun findBatch(userId: UUID, id: UUID) = batchStore[id]?.takeIf { it.userId == userId }
    override suspend fun batches(userId: UUID, limit: Int) = batchStore.values.filter { it.userId == userId }.sortedByDescending { it.createdAt }.take(limit)

    override suspend fun undo(userId: UUID, batchId: UUID, at: Instant): Boolean = undoNow(userId, batchId, at)

    @Synchronized
    private fun undoNow(userId: UUID, batchId: UUID, at: Instant): Boolean {
        val b = batchStore[batchId]?.takeIf { it.userId == userId && it.status == ImportStatus.APPLIED } ?: return false
        batchStore[batchId] = b.copy(status = ImportStatus.UNDONE, undoneAt = at)
        return true
    }
}
