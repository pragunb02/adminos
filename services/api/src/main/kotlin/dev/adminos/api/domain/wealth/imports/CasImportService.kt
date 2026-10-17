package dev.adminos.api.domain.wealth.imports

import dev.adminos.api.domain.wealth.AssetClass
import dev.adminos.api.domain.wealth.ImportBatch
import dev.adminos.api.domain.wealth.Instrument
import dev.adminos.api.domain.wealth.InstrumentRepository
import dev.adminos.api.domain.wealth.InvestmentAccount
import dev.adminos.api.domain.wealth.InvestmentAccountKind
import dev.adminos.api.domain.wealth.InvestmentAccountRepository
import dev.adminos.api.domain.wealth.InvestmentLedger
import dev.adminos.api.domain.wealth.InvestmentTransaction
import dev.adminos.api.domain.wealth.InvestmentTxnType
import dev.adminos.api.domain.wealth.UnitsEffect
import dev.adminos.api.domain.wealth.WealthAudit
import dev.adminos.api.domain.wealth.WealthAuditActions
import dev.adminos.api.domain.wealth.prices.PriceService
import dev.adminos.api.infrastructure.crypto.IdentifierKind
import dev.adminos.api.infrastructure.crypto.WealthIdentifiers
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import java.util.UUID

/** One holding of an import, as the owner sees it (folio masked). */
data class ImportedHolding(val folioMasked: String, val amc: String, val status: HoldingStatus)

data class CasImportResult(val batch: ImportBatch, val holdings: List<ImportedHolding>) {
    val blocked: Int get() = holdings.count { it.status.blocked }
}

/**
 * Imports a CAMS / KFintech CAS into the ledger (AOS-805, D-79):
 * 1. the parser reads the file in memory (D-37); folio numbers are encrypted before anything is written;
 * 2. each folio is an investment account, each scheme an instrument (ISIN, else AMFI code);
 * 3. rows become ledger transactions with a source ref, so re-importing a file adds nothing (D-38 style occurrences);
 * 4. each holding is reconciled: the ledger's units on the statement date must equal the closing units, or the
 *    holding is blocked and shown in Review.
 * The batch can be undone ([undo]); every import, rejection and undo is audited with counts only.
 */
class CasImportService(
    private val reader: CasReader,
    private val accounts: InvestmentAccountRepository,
    private val instruments: InstrumentRepository,
    private val ledger: InvestmentLedger,
    private val checks: HoldingCheckRepository,
    private val reconciliation: ReconciliationService,
    private val identifiers: WealthIdentifiers,
    private val audit: WealthAudit,
    /** Keeps each statement's valuation NAV as a price of its date (AOS-810), so holdings are valued before the next AMFI file. */
    private val prices: PriceService,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(CasImportService::class.java)

    /** @throws CasParseException when the file can't be read (wrong password, not a CAS…); nothing is written then */
    suspend fun import(userId: UUID, file: ByteArray, password: String?): CasImportResult = try {
        record(userId, reader.read(file, password))
    } catch (e: CasParseException) {
        audit.record(userId, WealthAuditActions.IMPORT_REJECTED, "wealth_import", null, codes = mapOf("reason" to e.code.lowercase().take(40)))
        throw e
    }

    private suspend fun record(userId: UUID, parsed: ParsedCas): CasImportResult {
        val statementTo = parsed.statementTo
            ?: parsed.folios.flatMap { f -> f.schemes.mapNotNull { it.valuationDate } }.maxOrNull()
            ?: throw CasParseException("NO_STATEMENT_DATE", "The statement has no date to reconcile against", clientError = true)
        val now = clock.instant()
        val batchId = UUID.randomUUID()
        // Every folio number is validated and encrypted before anything is written.
        val newAccounts = parsed.folios.map { newAccount(userId, it) }

        val entries = mutableListOf<InvestmentTransaction>()
        val pending = mutableListOf<Pair<InvestmentAccount, HoldingCheck>>()
        for ((folio, draft) in parsed.folios.zip(newAccounts)) {
            val account = accounts.findOrCreate(draft)
            for (scheme in folio.schemes) {
                val instrument = instrumentFor(scheme)
                val (rows, issue) = if (instrument == null) emptyList<InvestmentTransaction>() to HoldingIssue.NOT_IDENTIFIED
                    else ledgerRows(userId, batchId, account, instrument, scheme)
                entries += rows
                pending += account to HoldingCheck(
                    userId = userId, batchId = batchId, accountId = account.id, instrumentId = instrument?.id, schemeName = scheme.name.ifBlank { "Unnamed scheme" },
                    statementFrom = parsed.statementFrom, statementTo = statementTo, openingUnits = scheme.openingUnits, closingUnits = scheme.closingUnits,
                    valuationDate = scheme.valuationDate, valuationNav = scheme.nav, valuationValue = scheme.value, issue = issue, createdAt = now
                )
            }
        }
        val batch = ImportBatch(
            id = batchId, userId = userId, readerId = parsed.readerId.lowercase().take(40), readerVersion = parsed.readerVersion.take(40),
            statementFrom = parsed.statementFrom, statementTo = statementTo, events = entries.size, added = 0, duplicates = 0, createdAt = now
        )
        val recorded = ledger.record(batch, entries)
        checks.saveAll(pending.map { it.second })
        prices.recordStatementNavs(pending.map { it.second })
        val statuses = reconciliation.forBatch(userId, batchId).associateBy { it.check.id }
        val holdings = pending.map { (account, check) -> ImportedHolding(account.identifier.masked, account.institution, checkNotNull(statuses[check.id])) }
        val result = CasImportResult(recorded.batch, holdings)
        audit.record(userId, WealthAuditActions.IMPORT, "wealth_import_batch", batchId,
            counts = mapOf("folios" to parsed.folios.size, "holdings" to holdings.size, "added" to recorded.added, "duplicates" to recorded.duplicates,
                "blocked" to result.blocked, "parserWarnings" to parsed.parseWarnings),
            codes = mapOf("reader" to batch.readerId, "issuer" to parsed.issuer.lowercase().filter { it.isLetter() }.take(20).ifEmpty { "unknown" }))
        log.info("CAS import {} for user {}: {} added, {} duplicates, {} blocked", batchId, userId, recorded.added, recorded.duplicates, result.blocked)
        return result
    }

    /** Undoes an import: its rows stop counting and its holdings are re-judged. @return false if it isn't the user's applied batch */
    suspend fun undo(userId: UUID, batchId: UUID): Boolean {
        val undone = ledger.undo(userId, batchId, clock.instant())
        if (undone) audit.record(userId, WealthAuditActions.IMPORT_UNDO, "wealth_import_batch", batchId)
        return undone
    }

    suspend fun batches(userId: UUID, limit: Int = 50) = ledger.batches(userId, limit)

    /** Every imported holding with its status now (blocked first): what Review shows. */
    suspend fun review(userId: UUID): List<ImportedHolding> {
        val byId = accounts.findByUser(userId).associateBy { it.id }
        return reconciliation.current(userId).mapNotNull { s ->
            byId[s.check.accountId]?.let { ImportedHolding(it.identifier.masked, it.institution, s) }
        }
    }

    /** A new account for [folio] (used only if the user has no account with its blind index yet). */
    private fun newAccount(userId: UUID, folio: CasFolio): InvestmentAccount {
        val id = UUID.randomUUID()
        val protected = try {
            identifiers.protect(IdentifierKind.FOLIO, folio.folioNumber, "investment_accounts:$id:identifier")
        } catch (e: IllegalArgumentException) {
            throw CasParseException("INVALID_FOLIO", "A folio number on the statement isn't valid", clientError = true)
        }
        return InvestmentAccount(id = id, userId = userId, kind = InvestmentAccountKind.MF_FOLIO,
            institution = folio.amc.ifBlank { "Unknown fund house" }, identifier = protected, createdAt = clock.instant())
    }

    private suspend fun instrumentFor(scheme: CasScheme): Instrument? {
        val isin = scheme.isin?.takeIf { it.matches(Instrument.ISIN) }
        val amfi = scheme.amfiCode?.takeIf { it.matches(Instrument.AMFI_CODE) }
        if (isin == null && amfi == null) return null
        return instruments.findOrCreate(Instrument(name = scheme.name.ifBlank { "Unnamed scheme" }.take(255), assetClass = assetClassOf(scheme),
            isin = isin, amfiCode = amfi, mfPlan = scheme.plan, createdAt = clock.instant()))
    }

    /**
     * The scheme's rows as ledger transactions, and the first issue found. A row that can't be a valid ledger row is
     * left out and makes the holding blocked (so reconciliation shows it), instead of failing the whole file.
     */
    private fun ledgerRows(userId: UUID, batchId: UUID, account: InvestmentAccount, instrument: Instrument, scheme: CasScheme): Pair<List<InvestmentTransaction>, HoldingIssue?> {
        var issue: HoldingIssue? = null
        val occurrences = mutableMapOf<String, Int>()
        val out = mutableListOf<InvestmentTransaction>()
        var running = scheme.openingUnits
        for (row in scheme.rows) {
            row.units?.let { running += it }
            if (row.balance != null && row.units != null && row.balance != running) {
                issue = issue ?: HoldingIssue.BALANCE_BREAK
                running = row.balance
            }
            val type = typeOf(row)
            if (type == null) {
                issue = issue ?: HoldingIssue.UNRECOGNISED_ROW
                continue
            }
            val identity = listOf(account.identifier.blindIndex, instrument.isin ?: instrument.amfiCode, row.date, type.wire, row.units, row.amount?.toPlainString(), row.nav).joinToString("|")
            val n = occurrences.getOrDefault(identity, 0)
            occurrences[identity] = n + 1
            val txn = runCatching {
                InvestmentTransaction(userId = userId, accountId = account.id, instrumentId = instrument.id, batchId = batchId, type = type, tradeDate = row.date,
                    units = row.units?.takeUnless { type.units == UnitsEffect.NONE && it.isZero }, unitPrice = row.nav,
                    amount = row.amount, sourceRef = sha256("$identity|$n"), createdAt = clock.instant())
            }.getOrNull()
            if (txn == null) issue = issue ?: HoldingIssue.INVALID_ROW else out += txn
        }
        return out to issue
    }

    /** The ledger type for a row; null when the reader didn't recognise a row that moves units. */
    private fun typeOf(row: CasRow): InvestmentTxnType? {
        if (row.kind == "unknown") return if (row.units == null || row.units.isZero) InvestmentTxnType.MISC else null
        val type = InvestmentTxnType.entries.firstOrNull { it.wire == row.kind } ?: return null
        // An informational row that moves units isn't informational.
        if (type == InvestmentTxnType.MISC && row.units != null && !row.units.isZero) return null
        return type
    }

    private fun sha256(s: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))

    companion object {
        /** From the SEBI category when the ISIN database knows it, else the tax type (EQUITY/DEBT); the owner can reclassify later (AOS-817). */
        fun assetClassOf(scheme: CasScheme): AssetClass {
            val category = scheme.category.orEmpty().lowercase()
            return when {
                "gold" in category -> AssetClass.GOLD
                "overseas" in category || "international" in category -> AssetClass.EQUITY_FOREIGN
                category.startsWith("equity") -> AssetClass.EQUITY_IN
                category.startsWith("debt") -> AssetClass.DEBT
                category.startsWith("hybrid") -> AssetClass.HYBRID
                scheme.schemeType == "EQUITY" -> AssetClass.EQUITY_IN
                scheme.schemeType == "DEBT" -> AssetClass.DEBT
                else -> AssetClass.OTHER
            }
        }
    }
}
