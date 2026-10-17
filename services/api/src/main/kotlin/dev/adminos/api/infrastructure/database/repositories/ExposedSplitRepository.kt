package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.splits.PriorState
import dev.adminos.api.domain.financial.splits.SplitEffect
import dev.adminos.api.domain.financial.splits.SplitEntry
import dev.adminos.api.domain.financial.splits.SplitImport
import dev.adminos.api.domain.financial.splits.SplitKind
import dev.adminos.api.domain.financial.splits.SplitRepository
import dev.adminos.api.domain.financial.splits.SplitSource
import dev.adminos.api.domain.financial.splits.SplitState
import dev.adminos.api.domain.financial.splits.bankAmountOf
import dev.adminos.api.infrastructure.crypto.TokenEncryptor
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.SplitEntriesTable as E
import dev.adminos.api.infrastructure.database.tables.SplitImportsTable as I
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Splitwise imports and entries on Postgres (AOS-911). Descriptions and counterparties can name friends, so they are
 * encrypted with [TokenEncryptor] (AES-256-GCM, bound to `split_entries:<id>:<field>`); everything else is plain so
 * it can be queried. An entry whose transaction was deleted reads back as unmatched (or ignored for a friend-paid share).
 */
class ExposedSplitRepository(private val encryptor: TokenEncryptor) : SplitRepository {

    private companion object {
        val OPEN = listOf(SplitState.APPLIED, SplitState.SUGGESTED).map(::db)
        val MATCHED_EFFECTS = SplitEffect.entries.filter { it.needsMatch }.map(::db)
        const val REASON_MAX = 200
        fun db(e: Enum<*>) = e.name.lowercase()
    }

    override suspend fun saveImport(import: SplitImport): SplitImport = dbQuery {
        I.insert {
            it[id] = import.id
            it[userId] = import.userId
            it[sourceName] = db(import.source)
            it[rowsRead] = import.rowsRead
            it[createdAt] = import.createdAt
        }
        import
    }

    override suspend fun findImport(id: UUID): SplitImport? = dbQuery {
        I.selectAll().where { I.id eq id }.singleOrNull()?.toImport()
    }

    override suspend fun imports(userId: UUID): List<SplitImport> = dbQuery {
        I.selectAll().where { I.userId eq userId }.orderBy(I.createdAt, SortOrder.DESC).map { it.toImport() }
    }

    override suspend fun deleteImport(id: UUID) {
        dbQuery {
            E.deleteWhere { E.importId eq id }
            I.deleteWhere { I.id eq id }
        }
    }

    override suspend fun save(entry: SplitEntry): SplitEntry = dbQuery {
        E.insert {
            it[id] = entry.id
            it[userId] = entry.userId
            it[importId] = entry.importId
            it[sourceName] = db(entry.source)
            it[sourceRef] = entry.sourceRef
            it[kind] = db(entry.kind)
            it[effect] = db(entry.effect)
            it[entryDate] = entry.date
            it[splitwiseCategory] = entry.category
            it[cost] = entry.cost
            it[currency] = entry.currency
            it[ownerNet] = entry.ownerNet
            it[ownerShare] = entry.ownerShare
            it[bankAmount] = bankAmountOf(entry)
            it[createdAt] = entry.createdAt
            writeMutable(it, entry, entry.updatedAt)
        }
        entry
    }

    override suspend fun update(entry: SplitEntry): SplitEntry = dbQuery {
        val now = Instant.now()
        E.update({ E.id eq entry.id }) { writeMutable(it, entry, now) }
        entry.copy(updatedAt = now)
    }

    /** What changes over an entry's life (and the encrypted text, re-sealed with a fresh IV each write). */
    private fun writeMutable(it: UpdateBuilder<*>, e: SplitEntry, at: Instant) {
        it[E.state] = db(e.state)
        it[E.descriptionEnc] = encryptor.encrypt(e.description, context(e.id, "description"))
        it[E.counterpartyEnc] = e.counterparty?.let { c -> encryptor.encrypt(c, context(e.id, "counterparty")) }
        it[E.transactionId] = e.transactionId
        it[E.createdTransaction] = e.createdTransaction
        it[E.priorNature] = e.prior?.nature?.let(::db)
        it[E.priorCategory] = e.prior?.category?.let(::db)
        it[E.priorSubcategory] = e.prior?.subcategory
        it[E.priorCategorySource] = e.prior?.categorySource
        it[E.rejectedIds] = e.rejected.sorted().joinToString(",")
        it[E.score] = e.score
        it[E.reason] = e.reason?.take(REASON_MAX)
        it[E.updatedAt] = at
    }

    override suspend fun findById(id: UUID): SplitEntry? = dbQuery {
        E.selectAll().where { E.id eq id }.singleOrNull()?.toEntry()
    }

    override suspend fun findBySourceRefs(userId: UUID, refs: Collection<String>): List<SplitEntry> {
        if (refs.isEmpty()) return emptyList()
        return dbQuery {
            refs.distinct().chunked(1_000).flatMap { chunk ->
                E.selectAll().where { (E.userId eq userId) and (E.sourceRef inList chunk) }.map { it.toEntry() }
            }
        }
    }

    override suspend fun findByUser(userId: UUID, state: SplitState?, limit: Int): List<SplitEntry> = dbQuery {
        val query = E.selectAll().where { E.userId eq userId }
        if (state != null) {
            // Also rows whose transaction is gone, which read back as unmatched/ignored.
            val gone = (E.state inList OPEN) and E.transactionId.isNull()
            query.andWhere { (E.state eq db(state)) or (if (state in setOf(SplitState.UNMATCHED, SplitState.IGNORED)) gone else Op.FALSE) }
        }
        query.orderBy(E.entryDate to SortOrder.DESC, E.createdAt to SortOrder.DESC, E.id to SortOrder.ASC)
            .map { it.toEntry() }.filter { state == null || it.state == state }.take(limit)
    }

    override suspend fun findByImport(importId: UUID): List<SplitEntry> = dbQuery {
        E.selectAll().where { E.importId eq importId }.map { it.toEntry() }
    }

    override suspend fun findUnmatched(userId: UUID, bankAmount: BigDecimal, from: LocalDate, to: LocalDate): List<SplitEntry> = dbQuery {
        E.selectAll().where {
            (E.userId eq userId) and (E.state eq db(SplitState.UNMATCHED)) and (E.effect inList MATCHED_EFFECTS) and
                (E.bankAmount eq bankAmount) and (E.entryDate greaterEq from) and (E.entryDate lessEq to)
        }.orderBy(E.entryDate to SortOrder.ASC, E.id to SortOrder.ASC).map { it.toEntry() }
    }

    override suspend fun heldTransactions(userId: UUID): Set<UUID> = dbQuery {
        E.select(E.transactionId).where { (E.userId eq userId) and (E.state inList OPEN) and E.transactionId.isNotNull() }
            .mapNotNull { it[E.transactionId] }.toSet()
    }

    override suspend fun balances(userId: UUID): Map<String, BigDecimal> = dbQuery {
        val total = E.ownerNet.sum()
        E.select(E.currency, total).where { E.userId eq userId }.groupBy(E.currency)
            .associate { it[E.currency] to (it[total] ?: BigDecimal.ZERO).setScale(2) }
    }

    override suspend fun countByState(userId: UUID): Map<SplitState, Int> = dbQuery {
        E.select(E.state, E.effect, E.transactionId).where { E.userId eq userId }
            .map { readState(it[E.state], it[E.effect], it[E.transactionId]) }
            .groupingBy { it }.eachCount()
    }

    private fun readState(state: String, effect: String, transactionId: UUID?): SplitState {
        val s = SplitState.valueOf(state.uppercase())
        if (transactionId != null || db(s) !in OPEN) return s
        return if (effect == db(SplitEffect.OWED_SPEND)) SplitState.IGNORED else SplitState.UNMATCHED
    }

    private fun context(id: UUID, field: String) = "split_entries:$id:$field"

    private fun ResultRow.toImport() = SplitImport(
        id = this[I.id], userId = this[I.userId], source = SplitSource.valueOf(this[I.sourceName].uppercase()),
        rowsRead = this[I.rowsRead], createdAt = this[I.createdAt]
    )

    private fun ResultRow.toEntry(): SplitEntry {
        val id = this[E.id]
        val prior = this[E.priorNature]?.let { n ->
            PriorState(TransactionNature.valueOf(n.uppercase()),
                TransactionCategory.valueOf((this[E.priorCategory] ?: db(TransactionCategory.OTHER)).uppercase()),
                this[E.priorSubcategory], this[E.priorCategorySource] ?: "rules")
        }
        return SplitEntry(
            id = id, userId = this[E.userId], importId = this[E.importId],
            source = SplitSource.valueOf(this[E.sourceName].uppercase()), sourceRef = this[E.sourceRef],
            kind = SplitKind.valueOf(this[E.kind].uppercase()), effect = SplitEffect.valueOf(this[E.effect].uppercase()),
            state = readState(this[E.state], this[E.effect], this[E.transactionId]),
            date = this[E.entryDate],
            description = encryptor.decrypt(this[E.descriptionEnc], context(id, "description")),
            counterparty = this[E.counterpartyEnc]?.let { encryptor.decrypt(it, context(id, "counterparty")) },
            category = this[E.splitwiseCategory], cost = this[E.cost], currency = this[E.currency],
            ownerNet = this[E.ownerNet], ownerShare = this[E.ownerShare],
            transactionId = this[E.transactionId], createdTransaction = this[E.createdTransaction], prior = prior,
            rejected = this[E.rejectedIds].split(',').filter { it.isNotBlank() }.map(UUID::fromString).toSet(),
            score = this[E.score], reason = this[E.reason],
            createdAt = this[E.createdAt], updatedAt = this[E.updatedAt]
        )
    }
}
