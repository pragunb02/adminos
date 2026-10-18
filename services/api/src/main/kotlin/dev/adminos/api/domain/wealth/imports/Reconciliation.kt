package dev.adminos.api.domain.wealth.imports

import dev.adminos.api.domain.wealth.ImportStatus
import dev.adminos.api.domain.wealth.InvestmentLedger
import dev.adminos.api.domain.wealth.InvestmentTransaction
import dev.adminos.api.domain.wealth.Quantity
import dev.adminos.api.domain.wealth.UnitPrice
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Why a holding can't be trusted from its statement alone, decided at import time. */
enum class HoldingIssue(val text: String) {
    /** The scheme has no ISIN or AMFI code, so it can't be priced or matched. */
    NOT_IDENTIFIED("This scheme couldn't be identified (no ISIN or AMFI code on the statement)"),
    /** A row the reader didn't recognise moved units. */
    UNRECOGNISED_ROW("A transaction on the statement wasn't recognised"),
    /** A row contradicted itself (e.g. a purchase that removed units). */
    INVALID_ROW("A transaction on the statement doesn't add up"),
    /** A printed running unit balance disagreed with the rows before it. */
    BALANCE_BREAK("The statement's running unit balance doesn't follow its own rows");

    val wire: String get() = name.lowercase()
}

/**
 * What one import said about one holding (folio × scheme): the statement's closing units on its date, the
 * valuation it printed, and any issue found while reading it (AOS-805). The status itself is computed against the
 * current ledger ([ReconciliationService]), so undoing an import or importing older history updates it.
 */
data class HoldingCheck(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val batchId: UUID,
    val accountId: UUID,
    val instrumentId: UUID?,
    val schemeName: String,
    val statementFrom: LocalDate?,
    val statementTo: LocalDate,
    val openingUnits: Quantity,
    val closingUnits: Quantity,
    val valuationDate: LocalDate?,
    val valuationNav: UnitPrice?,
    val valuationValue: BigDecimal?,
    val issue: HoldingIssue?,
    val createdAt: Instant = Instant.now()
) {
    init {
        require(schemeName.isNotBlank() && schemeName.length <= 255) { "scheme name must be 1–255 characters" }
        require(instrumentId != null || issue == HoldingIssue.NOT_IDENTIFIED) { "a check without an instrument must say why" }
    }

    /** The holding this check is about: an instrument in an account, or (unidentified) a scheme name in an account. */
    val key: String get() = "$accountId:${instrumentId ?: schemeName}"
}

interface HoldingCheckRepository {
    suspend fun saveAll(checks: List<HoldingCheck>)
    /** The user's checks from applied (not undone) batches. */
    suspend fun applied(userId: UUID): List<HoldingCheck>
    suspend fun forBatch(userId: UUID, batchId: UUID): List<HoldingCheck>
}

class InMemoryHoldingCheckRepository(private val ledger: InvestmentLedger) : HoldingCheckRepository {
    private val store = ConcurrentHashMap<UUID, HoldingCheck>()
    override suspend fun saveAll(checks: List<HoldingCheck>) = checks.forEach { store[it.id] = it }
    override suspend fun applied(userId: UUID): List<HoldingCheck> = store.values.filter { it.userId == userId }
        .filter { ledger.findBatch(userId, it.batchId)?.status == ImportStatus.APPLIED }
    override suspend fun forBatch(userId: UUID, batchId: UUID) = store.values.filter { it.userId == userId && it.batchId == batchId }
}

enum class ReconciliationStatus { RECONCILED, BLOCKED; val wire: String get() = name.lowercase() }

/** A holding's current standing: [ledgerUnits] are the ledger's units on the statement date. */
data class HoldingStatus(val check: HoldingCheck, val ledgerUnits: Quantity, val status: ReconciliationStatus, val reason: String?) {
    val blocked: Boolean get() = status == ReconciliationStatus.BLOCKED
}

/**
 * Mandatory reconciliation (D-79, WEALTH-RESEARCH §1.1): the units the ledger derives for a holding on a statement's
 * date must equal the statement's closing units exactly, or the holding is blocked and shown in Review, never
 * silently used. The latest statement per holding is the one that counts.
 */
class ReconciliationService(private val checks: HoldingCheckRepository, private val ledger: InvestmentLedger) {

    /** Every holding the user has imported, with its status now. */
    suspend fun current(userId: UUID): List<HoldingStatus> {
        val latest = checks.applied(userId)
            .groupBy { it.key }
            .map { (_, list) -> list.maxWith(compareBy<HoldingCheck>({ it.statementTo }, { it.createdAt })) }
        val rows = ledger.active(userId)
        return latest.map { evaluate(it, rows) }.sortedWith(compareBy({ !it.blocked }, { it.check.schemeName }))
    }

    /** The statuses of one import's holdings, judged against the ledger as it is now. */
    suspend fun forBatch(userId: UUID, batchId: UUID): List<HoldingStatus> {
        val rows = ledger.active(userId)
        return checks.forBatch(userId, batchId).map { evaluate(it, rows) }
    }

    fun evaluate(check: HoldingCheck, rows: List<InvestmentTransaction>): HoldingStatus {
        val units = if (check.instrumentId == null) Quantity.ZERO else rows
            .filter { it.accountId == check.accountId && it.instrumentId == check.instrumentId && !it.tradeDate.isAfter(check.statementTo) }
            .mapNotNull { it.units }
            .fold(Quantity.ZERO) { acc, q -> acc + q }
        check.issue?.let { return HoldingStatus(check, units, ReconciliationStatus.BLOCKED, it.text) }
        if (units == check.closingUnits) return HoldingStatus(check, units, ReconciliationStatus.RECONCILED, null)
        val reason = if (check.openingUnits.signum > 0 && check.statementFrom != null)
            "Units don't add up: the history before ${check.statementFrom} is missing. Import a statement that starts from your first investment"
        else "Units don't add up: the statement says ${check.closingUnits} units, the transactions give $units"
        return HoldingStatus(check, units, ReconciliationStatus.BLOCKED, reason)
    }
}
