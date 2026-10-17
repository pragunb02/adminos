package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.wealth.AssetClass
import dev.adminos.api.domain.wealth.FxRate
import dev.adminos.api.domain.wealth.FxRateType
import dev.adminos.api.domain.wealth.ImportBatch
import dev.adminos.api.domain.wealth.ImportStatus
import dev.adminos.api.domain.wealth.Instrument
import dev.adminos.api.domain.wealth.InstrumentRepository
import dev.adminos.api.domain.wealth.InvestmentAccount
import dev.adminos.api.domain.wealth.InvestmentAccountKind
import dev.adminos.api.domain.wealth.InvestmentAccountRepository
import dev.adminos.api.domain.wealth.InvestmentLedger
import dev.adminos.api.domain.wealth.InvestmentTransaction
import dev.adminos.api.domain.wealth.InvestmentTxnType
import dev.adminos.api.domain.wealth.MfPlan
import dev.adminos.api.domain.wealth.ProtectedIdentifier
import dev.adminos.api.domain.wealth.Quantity
import dev.adminos.api.domain.wealth.RecordResult
import dev.adminos.api.domain.wealth.UnitPrice
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.dbQueryReadCommitted
import dev.adminos.api.infrastructure.database.tables.InstrumentsTable
import dev.adminos.api.infrastructure.database.tables.InvestmentAccountsTable
import dev.adminos.api.infrastructure.database.tables.InvestmentTransactionsTable
import dev.adminos.api.infrastructure.database.tables.UsersTable
import dev.adminos.api.infrastructure.database.tables.WealthImportBatchesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.Currency
import java.util.UUID

/** Postgres investment accounts (AOS-802): the unique (user, kind, blind index) makes [findOrCreate] race-free. */
class ExposedInvestmentAccountRepository : InvestmentAccountRepository {
    private val t = InvestmentAccountsTable

    override suspend fun findOrCreate(account: InvestmentAccount): InvestmentAccount = dbQuery {
        t.insertIgnore {
            it[id] = account.id; it[userId] = account.userId; it[kind] = account.kind.wire; it[institution] = account.institution
            it[currency] = account.currency.currencyCode; it[identifierEnc] = account.identifier.ciphertext
            it[identifierHmac] = account.identifier.blindIndex; it[identifierLast4] = account.identifier.last4
            it[ownerShare] = account.ownerShare; it[fundingAccountId] = account.fundingAccountId; it[createdAt] = account.createdAt
        }
        t.selectAll().where { (t.userId eq account.userId) and (t.kind eq account.kind.wire) and (t.identifierHmac eq account.identifier.blindIndex) }
            .single().toAccount()
    }

    override suspend fun findById(userId: UUID, id: UUID): InvestmentAccount? = dbQuery {
        t.selectAll().where { (t.id eq id) and (t.userId eq userId) }.singleOrNull()?.toAccount()
    }

    override suspend fun findByUser(userId: UUID): List<InvestmentAccount> = dbQuery {
        t.selectAll().where { t.userId eq userId }.orderBy(t.createdAt to SortOrder.ASC).map { it.toAccount() }
    }

    private fun ResultRow.toAccount() = InvestmentAccount(
        id = this[t.id], userId = this[t.userId], kind = InvestmentAccountKind.fromWire(this[t.kind]), institution = this[t.institution],
        currency = Currency.getInstance(this[t.currency]),
        identifier = ProtectedIdentifier(this[t.identifierEnc], this[t.identifierHmac], this[t.identifierLast4]),
        ownerShare = this[t.ownerShare], fundingAccountId = this[t.fundingAccountId], createdAt = this[t.createdAt]
    )
}

/** Postgres instruments: the ISIN is unique, so a concurrent first import of a scheme resolves to one row. */
class ExposedInstrumentRepository : InstrumentRepository {
    private val t = InstrumentsTable

    override suspend fun findOrCreate(instrument: Instrument): Instrument = dbQuery {
        find(instrument) ?: run {
            t.insertIgnore {
                it[id] = instrument.id; it[name] = instrument.name; it[assetClass] = instrument.assetClass.wire; it[isin] = instrument.isin
                it[amfiCode] = instrument.amfiCode; it[exchange] = instrument.exchange; it[ticker] = instrument.ticker
                it[currency] = instrument.currency.currencyCode; it[mfPlan] = instrument.mfPlan?.wire; it[createdAt] = instrument.createdAt
            }
            find(instrument) ?: error("instrument neither found nor inserted")
        }
    }

    private fun find(i: Instrument): Instrument? {
        val where = when {
            i.isin != null -> t.isin eq i.isin
            i.amfiCode != null -> t.amfiCode eq i.amfiCode
            else -> (t.ticker eq i.ticker) and (if (i.exchange == null) t.exchange.isNull() else t.exchange eq i.exchange)
        }
        return t.selectAll().where { where }.orderBy(t.createdAt to SortOrder.ASC, t.isin to SortOrder.ASC).limit(1).singleOrNull()?.toInstrument()
    }

    override suspend fun findByIds(ids: Collection<UUID>): List<Instrument> = if (ids.isEmpty()) emptyList() else dbQuery {
        t.selectAll().where { t.id inList ids.toSet() }.map { it.toInstrument() }
    }

    override suspend fun withAmfiCode(): List<Instrument> = dbQuery {
        t.selectAll().where { t.amfiCode.isNotNull() }.orderBy(t.amfiCode to SortOrder.ASC).map { it.toInstrument() }
    }

    private fun ResultRow.toInstrument() = Instrument(
        id = this[t.id], name = this[t.name], assetClass = AssetClass.fromWire(this[t.assetClass]), isin = this[t.isin],
        amfiCode = this[t.amfiCode], exchange = this[t.exchange], ticker = this[t.ticker], currency = Currency.getInstance(this[t.currency]),
        mfPlan = this[t.mfPlan]?.let { p -> MfPlan.entries.first { it.wire == p } }, createdAt = this[t.createdAt]
    )
}

/**
 * Postgres ledger (AOS-802). [record] runs at READ COMMITTED and first locks the user's row, so two imports of the
 * same file serialise and the second sees the first's rows (under REPEATABLE READ it would read an older snapshot).
 * The database itself refuses updates to ledger rows (trigger in V70).
 */
class ExposedInvestmentLedger : InvestmentLedger {
    private val tx = InvestmentTransactionsTable
    private val b = WealthImportBatchesTable

    override suspend fun record(batch: ImportBatch, entries: List<InvestmentTransaction>): RecordResult {
        require(entries.all { it.userId == batch.userId && it.batchId == batch.id }) { "every entry belongs to the batch and its user" }
        return dbQueryReadCommitted {
            UsersTable.selectAll().where { UsersTable.id eq batch.userId }.forUpdate(ForUpdateOption.PostgreSQL.ForUpdate()).single()
            val refs = entries.map { it.sourceRef }.toSet()
            val known = if (refs.isEmpty()) emptySet() else activeQuery(batch.userId) { tx.sourceRef inList refs }.map { it[tx.sourceRef] }.toSet()
            val seen = known.toMutableSet()
            val fresh = entries.filter { seen.add(it.sourceRef) }
            val saved = batch.copy(added = fresh.size, duplicates = entries.size - fresh.size)
            b.insert {
                it[id] = saved.id; it[userId] = saved.userId; it[readerId] = saved.readerId; it[readerVersion] = saved.readerVersion
                it[statementFrom] = saved.statementFrom; it[statementTo] = saved.statementTo; it[status] = saved.status.wire
                it[events] = saved.events; it[added] = saved.added; it[duplicates] = saved.duplicates; it[createdAt] = saved.createdAt
                it[undoneAt] = saved.undoneAt
            }
            tx.batchInsert(fresh, shouldReturnGeneratedValues = false) { e ->
                this[tx.id] = e.id; this[tx.userId] = e.userId; this[tx.accountId] = e.accountId; this[tx.instrumentId] = e.instrumentId
                this[tx.batchId] = e.batchId; this[tx.txnType] = e.type.wire; this[tx.tradeDate] = e.tradeDate
                this[tx.units] = e.units?.value; this[tx.unitPrice] = e.unitPrice?.value; this[tx.amount] = e.amount
                this[tx.currency] = e.currency.currencyCode; this[tx.fxRate] = e.fx?.rate; this[tx.fxRateType] = e.fx?.type?.name?.lowercase()
                this[tx.fxRateDate] = e.fx?.date; this[tx.inrAmount] = e.inrAmount; this[tx.sourceRef] = e.sourceRef
                this[tx.supersedesId] = e.supersedesId; this[tx.createdAt] = e.createdAt
            }
            RecordResult(saved, fresh.size, entries.size - fresh.size)
        }
    }

    override suspend fun active(userId: UUID): List<InvestmentTransaction> = dbQuery {
        activeQuery(userId) { null }.map { it.toTxn() }
    }

    /** The user's rows that are in an applied batch or in none, oldest first; [extra] narrows it further. */
    private fun activeQuery(userId: UUID, extra: () -> org.jetbrains.exposed.v1.core.Op<Boolean>?) =
        tx.join(b, org.jetbrains.exposed.v1.core.JoinType.LEFT, tx.batchId, b.id)
            .selectAll()
            .where {
                val base = (tx.userId eq userId) and (tx.batchId.isNull() or (b.status eq ImportStatus.APPLIED.wire))
                extra()?.let { base and it } ?: base
            }
            .orderBy(tx.tradeDate to SortOrder.ASC, tx.createdAt to SortOrder.ASC)

    override suspend fun findBatch(userId: UUID, id: UUID): ImportBatch? = dbQuery {
        b.selectAll().where { (b.id eq id) and (b.userId eq userId) }.singleOrNull()?.toBatch()
    }

    override suspend fun batches(userId: UUID, limit: Int): List<ImportBatch> = dbQuery {
        b.selectAll().where { b.userId eq userId }.orderBy(b.createdAt to SortOrder.DESC).limit(limit).map { it.toBatch() }
    }

    override suspend fun undo(userId: UUID, batchId: UUID, at: Instant): Boolean = dbQuery {
        b.update({ (b.id eq batchId) and (b.userId eq userId) and (b.status eq ImportStatus.APPLIED.wire) }) {
            it[status] = ImportStatus.UNDONE.wire; it[undoneAt] = at
        } > 0
    }

    private fun ResultRow.toBatch() = ImportBatch(
        id = this[b.id], userId = this[b.userId], readerId = this[b.readerId], readerVersion = this[b.readerVersion],
        statementFrom = this[b.statementFrom], statementTo = this[b.statementTo], status = ImportStatus.valueOf(this[b.status].uppercase()),
        events = this[b.events], added = this[b.added], duplicates = this[b.duplicates], createdAt = this[b.createdAt], undoneAt = this[b.undoneAt]
    )

    private fun ResultRow.toTxn(): InvestmentTransaction {
        val currency = Currency.getInstance(this[tx.currency])
        val fx = this[tx.fxRate]?.let { rate ->
            FxRate(currency, dev.adminos.api.domain.wealth.INR, rate, FxRateType.valueOf(checkNotNull(this[tx.fxRateType]).uppercase()), checkNotNull(this[tx.fxRateDate]))
        }
        return InvestmentTransaction(
            id = this[tx.id], userId = this[tx.userId], accountId = this[tx.accountId], instrumentId = this[tx.instrumentId],
            batchId = this[tx.batchId], type = InvestmentTxnType.fromWire(this[tx.txnType]), tradeDate = this[tx.tradeDate],
            units = this[tx.units]?.let(Quantity::of), unitPrice = this[tx.unitPrice]?.let(UnitPrice::of), amount = this[tx.amount],
            currency = currency, fx = fx, inrAmount = this[tx.inrAmount], sourceRef = this[tx.sourceRef],
            supersedesId = this[tx.supersedesId], createdAt = this[tx.createdAt]
        )
    }
}
