package dev.adminos.api.domain.financial.splits

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.ingestion.pipeline.TransactionCreatedListener
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

data class SplitImportResult(
    val importId: UUID,
    /** Rows read from the export (expenses and payments). */
    val rows: Int,
    val alreadyImported: Int,
    /** Already-imported rows whose numbers differ now: undo that import and import again to take the new numbers. */
    val changed: Int,
    val applied: Int,
    val suggested: Int,
    val unmatched: Int,
    val noEffect: Int,
    val unreadableRows: Int
)

/** An entry with the transaction it is about (when there is one), for Review. */
data class SplitEntryView(val entry: SplitEntry, val transaction: Transaction?)

data class SplitSummary(val balances: Map<String, BigDecimal>, val counts: Map<SplitState, Int>)

sealed interface SplitAction {
    data object Done : SplitAction
    data object NotFound : SplitAction
    /** The entry is in a state where this can't be done, or the transaction can't be this entry's. */
    data class Refused(val reason: String) : SplitAction
}

/**
 * Splitwise shared expenses → what the owner really spent (AOS-911, D-111).
 * - A bill the owner paid for everyone: the bank debit counts only their share; the rest is owed by friends.
 * - A bill a friend paid: the owner's share becomes a spend (source `splitwise`), owed to that friend.
 * - A settle-up: the UPI transfer to or from the friend becomes a settlement (neither spending nor income).
 * Matches that aren't clear wait in Review; anything applied can be undone, alone or with its whole import.
 * Friends' names are used in memory to match settle-ups; they are never logged and never sent to a model.
 */
class SplitService(
    private val repository: SplitRepository,
    private val transactions: TransactionRepository,
    private val categorizer: CategorizationService,
    private val calendar: UserCalendar,
    private val clock: Clock = Clock.systemUTC(),
    private val unitOfWork: suspend (suspend () -> Unit) -> Unit = { it() }
) : TransactionCreatedListener {

    companion object {
        const val CURRENCY = "INR"
        const val MERCHANT = "Splitwise"
        const val SETTLEMENT_SUBCATEGORY = "splitwise_settlement"
        const val CATEGORY_SOURCE = "splitwise"
        /** Noon on the Splitwise date: the date is all Splitwise gives, and noon keeps it on that day in the user's zone. */
        private val SPEND_TIME: LocalTime = LocalTime.NOON
    }

    private val matcher = SplitMatcher()

    suspend fun importCsv(userId: UUID, file: ByteArray, me: String): SplitImportResult =
        import(userId, SplitSource.SPLITWISE_CSV, SplitwiseCsv.parse(file, me))

    /** Imports already-read entries from any source adapter (the CSV now; the API later). */
    suspend fun import(userId: UUID, source: SplitSource, export: SplitwiseCsvExport): SplitImportResult {
        val zone = calendar.zoneOf(userId)
        val existing = repository.findBySourceRefs(userId, export.entries.map { it.sourceRef }).associateBy { it.sourceRef }
        val fresh = export.entries.filter { it.sourceRef !in existing }
        val changed = export.entries.count { e -> existing[e.sourceRef]?.let { it.ownerNet.compareTo(e.ownerNet) != 0 } ?: false }
        val import = repository.saveImport(SplitImport(userId = userId, source = source, rowsRead = export.entries.size, createdAt = clock.instant()))

        val held = repository.heldTransactions(userId).toMutableSet()
        val stored = fresh.sortedBy { it.date }.map { shared ->
            val entry = newEntry(userId, import.id, source, shared)
            when {
                entry.state == SplitState.NO_EFFECT -> repository.save(entry)
                entry.effect == SplitEffect.OWED_SPEND -> recordOwedSpend(entry, zone)
                else -> match(repository.save(entry), zone, held)
            }.also { s -> s.transactionId?.let(held::add) }
        }
        val by = stored.groupingBy { it.state }.eachCount()
        return SplitImportResult(
            importId = import.id, rows = export.entries.size, alreadyImported = export.entries.size - fresh.size, changed = changed,
            applied = by[SplitState.APPLIED] ?: 0, suggested = by[SplitState.SUGGESTED] ?: 0, unmatched = by[SplitState.UNMATCHED] ?: 0,
            noEffect = by[SplitState.NO_EFFECT] ?: 0, unreadableRows = export.unreadableRows
        )
    }

    private fun newEntry(userId: UUID, importId: UUID, source: SplitSource, s: SharedEntry): SplitEntry {
        val (state, reason) = when {
            s.effect == SplitEffect.NONE -> SplitState.NO_EFFECT to "doesn't change what you spent"
            s.currency != CURRENCY -> SplitState.NO_EFFECT to "not in rupees"
            else -> SplitState.UNMATCHED to null
        }
        return SplitEntry(
            userId = userId, importId = importId, source = source, sourceRef = s.sourceRef, kind = s.kind, effect = s.effect,
            state = state, date = s.date, description = s.description, counterparty = s.counterparty, category = s.category,
            cost = s.cost, currency = s.currency, ownerNet = s.ownerNet, ownerShare = s.ownerShare, reason = reason,
            createdAt = clock.instant(), updatedAt = clock.instant()
        )
    }

    /**
     * A friend paid: the owner's share is a spend on the Splitwise date. Its name carries no personal data.
     * [stored] says whether the entry row exists already (an ignored entry opened again).
     */
    private suspend fun recordOwedSpend(entry: SplitEntry, zone: ZoneId, stored: Boolean = false): SplitEntry {
        val mapped = SplitwiseCategories.categoryOf(entry.category)
        val category = if (mapped != TransactionCategory.OTHER) mapped else categorizer.builtIn(entry.description).category
        val spend = Transaction(
            userId = entry.userId, sourceType = SplitMatcher.SPLITWISE_SOURCE, sourceRef = entry.sourceRef,
            type = TransactionType.DEBIT, nature = TransactionNature.SPEND, amount = entry.ownerShare, currency = entry.currency,
            merchantName = MERCHANT, category = category, subcategory = entry.category, categorySource = CATEGORY_SOURCE,
            transactedAt = entry.date.atTime(SPEND_TIME).atZone(zone).toInstant(), createdAt = clock.instant(), updatedAt = clock.instant()
        )
        val applied = entry.copy(state = SplitState.APPLIED, transactionId = spend.id, createdTransaction = true, reason = "your share, owed to a friend")
        var saved = applied
        unitOfWork {
            transactions.save(spend)
            saved = if (stored) repository.update(applied) else repository.save(applied)
        }
        return saved
    }

    /** Looks for the entry's transaction and applies, suggests or leaves it unmatched. [held] grows with what it takes. */
    private suspend fun match(entry: SplitEntry, zone: ZoneId, held: MutableSet<UUID>): SplitEntry {
        val query = queryOf(entry, held + entry.rejected)
        return when (val m = matcher.decide(query, candidates(query, zone))) {
            SplitMatch.None -> entry
            is SplitMatch.Suggest -> repository.update(entry.copy(state = SplitState.SUGGESTED, transactionId = m.transaction.id, score = m.score, reason = m.reason))
            is SplitMatch.Apply -> apply(entry, m.transaction, m.score, m.reason)
        }.also { it.transactionId?.let(held::add) }
    }

    private fun queryOf(e: SplitEntry, excluded: Set<UUID>) = MatchQuery(
        userId = e.userId, effect = e.effect, date = e.date, bankAmount = bankAmountOf(e), currency = e.currency,
        description = e.description, category = e.category, counterparty = e.counterparty, excluded = excluded
    )

    private suspend fun candidates(q: MatchQuery, zone: ZoneId): List<Transaction> {
        val days = matcher.window(q.effect)
        val type = if (q.effect == SplitEffect.SETTLE_IN) TransactionType.CREDIT else TransactionType.DEBIT
        val from = q.date.plusDays(days.first).atStartOfDay(zone).toInstant()
        val to = q.date.plusDays(days.last + 1).atStartOfDay(zone).toInstant().minusNanos(1)
        return transactions.findMatchCandidates(q.userId, type, q.bankAmount, from, to)
    }

    /** Applies [entry] to [t] in one unit: the share, or the settlement, and the entry's new state. */
    private suspend fun apply(entry: SplitEntry, t: Transaction, score: Int?, reason: String): SplitEntry {
        var applied = entry
        unitOfWork {
            val prior = when (entry.effect) {
                SplitEffect.ADJUST_DEBIT -> { transactions.setOwnShare(t.id, entry.ownerShare); null }
                SplitEffect.SETTLE_OUT, SplitEffect.SETTLE_IN -> {
                    transactions.update(asSettlement(t))
                    PriorState(t.nature, t.category, t.subcategory, t.categorySource)
                }
                else -> error("${entry.effect} is never matched")
            }
            applied = repository.update(entry.copy(state = SplitState.APPLIED, transactionId = t.id, prior = prior,
                rejected = entry.rejected - t.id, score = score, reason = reason))
        }
        return applied
    }

    private fun asSettlement(t: Transaction) = t.copy(
        nature = TransactionNature.SETTLEMENT,
        category = if (t.categorySource == "user") t.category else TransactionCategory.TRANSFER,
        subcategory = if (t.categorySource == "user") t.subcategory else SETTLEMENT_SUBCATEGORY
    )

    /** Puts [t] back as it was before [entry] was applied. */
    private suspend fun revert(entry: SplitEntry, t: Transaction?) {
        if (t == null) return
        when (entry.effect) {
            SplitEffect.ADJUST_DEBIT -> transactions.setOwnShare(t.id, null)
            SplitEffect.SETTLE_OUT, SplitEffect.SETTLE_IN -> entry.prior?.let { p ->
                transactions.update(t.copy(nature = p.nature, category = p.category, subcategory = p.subcategory, categorySource = p.categorySource))
            }
            SplitEffect.OWED_SPEND -> transactions.delete(t.id)
            SplitEffect.NONE -> {}
        }
    }

    // ── Review ──

    suspend fun entries(userId: UUID, state: SplitState?, limit: Int): List<SplitEntryView> =
        repository.findByUser(userId, state, limit).map { e -> SplitEntryView(e, e.transactionId?.let { transactions.findById(it) }) }

    suspend fun summary(userId: UUID) = SplitSummary(repository.balances(userId), repository.countByState(userId))

    suspend fun imports(userId: UUID): List<SplitImport> = repository.imports(userId)

    /** Applies a suggested entry, or links an unmatched one to the transaction the owner picked. */
    suspend fun confirm(userId: UUID, entryId: UUID, transactionId: UUID? = null): SplitAction {
        val entry = owned(userId, entryId) ?: return SplitAction.NotFound
        if (!entry.effect.needsMatch || entry.state !in setOf(SplitState.SUGGESTED, SplitState.UNMATCHED)) {
            return SplitAction.Refused("This entry has nothing to confirm")
        }
        val targetId = transactionId ?: entry.transactionId ?: return SplitAction.Refused("Choose the transaction this entry is about")
        val t = transactions.findById(targetId)?.takeIf { it.userId == userId } ?: return SplitAction.Refused("That transaction isn't yours or doesn't exist")
        val heldByOthers = repository.heldTransactions(userId) - setOfNotNull(entry.transactionId)
        // A transaction the owner rejected earlier may still be chosen by hand.
        if (!matcher.eligible(queryOf(entry, heldByOthers), t)) {
            return SplitAction.Refused("That transaction doesn't fit this entry (amount, date, direction) or is already used")
        }
        apply(entry, t, entry.score, "confirmed by you")
        return SplitAction.Done
    }

    /** "Not this one": the suggestion is forgotten for good, and the next best transaction (if any) is suggested. */
    suspend fun reject(userId: UUID, entryId: UUID): SplitAction {
        val entry = owned(userId, entryId) ?: return SplitAction.NotFound
        val suggested = entry.transactionId
        if (entry.state != SplitState.SUGGESTED || suggested == null) return SplitAction.Refused("Only a suggestion can be rejected")
        val unmatched = repository.update(entry.copy(state = SplitState.UNMATCHED, transactionId = null, rejected = entry.rejected + suggested,
            score = null, reason = "rejected by you"))
        match(unmatched, calendar.zoneOf(userId), repository.heldTransactions(userId).toMutableSet())
        return SplitAction.Done
    }

    /** "Leave it": nothing is applied and it is no longer matched. */
    suspend fun ignore(userId: UUID, entryId: UUID): SplitAction {
        val entry = owned(userId, entryId) ?: return SplitAction.NotFound
        if (entry.state !in setOf(SplitState.SUGGESTED, SplitState.UNMATCHED)) return SplitAction.Refused("Only an open entry can be ignored")
        repository.update(entry.copy(state = SplitState.IGNORED, transactionId = null, reason = "ignored by you"))
        return SplitAction.Done
    }

    /**
     * Reverses an entry: an applied one is taken off its transaction (which is then never matched to it again);
     * an ignored one is opened again.
     */
    suspend fun undo(userId: UUID, entryId: UUID): SplitAction {
        val entry = owned(userId, entryId) ?: return SplitAction.NotFound
        val zone = calendar.zoneOf(userId)
        when (entry.state) {
            SplitState.APPLIED -> unitOfWork {
                val t = entry.transactionId?.let { transactions.findById(it) }
                val reopened = if (entry.effect == SplitEffect.OWED_SPEND) {
                    entry.copy(state = SplitState.IGNORED, transactionId = null, createdTransaction = false, prior = null, reason = "undone by you")
                } else {
                    entry.copy(state = SplitState.UNMATCHED, transactionId = null, prior = null,
                        rejected = entry.rejected + listOfNotNull(entry.transactionId), score = null, reason = "undone by you")
                }
                repository.update(reopened) // first, so a deleted spend is no longer referenced
                revert(entry, t)
            }
            SplitState.IGNORED ->
                if (entry.effect == SplitEffect.OWED_SPEND) recordOwedSpend(entry, zone, stored = true)
                else match(repository.update(entry.copy(state = SplitState.UNMATCHED, reason = null)), zone,
                    repository.heldTransactions(userId).toMutableSet())
            else -> return SplitAction.Refused("Only an applied or ignored entry can be undone")
        }
        return SplitAction.Done
    }

    /** Reverses everything one import did and forgets its entries, so the export can be imported afresh. */
    suspend fun undoImport(userId: UUID, importId: UUID): SplitAction {
        val import = repository.findImport(importId)?.takeIf { it.userId == userId } ?: return SplitAction.NotFound
        unitOfWork {
            val applied = repository.findByImport(import.id).filter { it.state == SplitState.APPLIED }
            // Entries go first (with the import), so no row still points at a spend about to be deleted.
            val targets = applied.map { it to it.transactionId?.let { id -> transactions.findById(id) } }
            repository.deleteImport(import.id)
            targets.forEach { (e, t) -> revert(e, t) }
        }
        return SplitAction.Done
    }

    private suspend fun owned(userId: UUID, id: UUID) = repository.findById(id)?.takeIf { it.userId == userId }

    // ── New bank transactions ──

    /**
     * A bank record that arrives after the Splitwise import (a statement uploaded later) is offered to the entries
     * still waiting for it. Idempotent: an entry already applied or suggested is no longer unmatched.
     */
    override suspend fun onCreated(transaction: Transaction) {
        val t = transaction
        if (t.sourceType == SplitMatcher.SPLITWISE_SOURCE || t.currency != CURRENCY) return
        val fits = (t.type == TransactionType.DEBIT && t.nature == TransactionNature.SPEND) ||
            (t.type == TransactionType.CREDIT && t.nature == TransactionNature.INCOME)
        if (!fits) return
        val zone = calendar.zoneOf(t.userId)
        val day: LocalDate = t.transactedAt.atZone(zone).toLocalDate()
        // Entry date = bank day minus the offset: bills −1…+3, settle-ups ±2.
        val waiting = repository.findUnmatched(t.userId, t.amount, day.minusDays(3), day.plusDays(2))
        if (waiting.isEmpty()) return
        val held = repository.heldTransactions(t.userId).toMutableSet()
        for (entry in waiting) {
            if (t.id in held) break
            match(entry, zone, held)
        }
    }
}
