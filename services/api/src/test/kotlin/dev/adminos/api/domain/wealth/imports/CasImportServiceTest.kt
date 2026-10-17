package dev.adminos.api.domain.wealth.imports

import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.wealth.AssetClass
import dev.adminos.api.domain.wealth.ImportStatus
import dev.adminos.api.domain.wealth.InMemoryInstrumentRepository
import dev.adminos.api.domain.wealth.InMemoryInvestmentAccountRepository
import dev.adminos.api.domain.wealth.InMemoryInvestmentLedger
import dev.adminos.api.domain.wealth.InstrumentRepository
import dev.adminos.api.domain.wealth.InvestmentAccountRepository
import dev.adminos.api.domain.wealth.InvestmentLedger
import dev.adminos.api.domain.wealth.InvestmentTxnType
import dev.adminos.api.domain.wealth.MfPlan
import dev.adminos.api.domain.wealth.Quantity
import dev.adminos.api.domain.wealth.WealthAudit
import dev.adminos.api.domain.wealth.WealthAuditActions
import dev.adminos.api.domain.wealth.WealthFixtures
import dev.adminos.api.domain.wealth.imports.CasFixtures.row
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A CAS import over in-memory stores (AOS-805); the same flow runs on Postgres in `CasImportIntegrationTest`. */
class CasImportServiceTest {

    /** The import service and its stores; [next] is what the fake parser returns next. */
    class Kit(
        val accounts: InvestmentAccountRepository = InMemoryInvestmentAccountRepository(),
        val instruments: InstrumentRepository = InMemoryInstrumentRepository(),
        val ledger: InvestmentLedger = InMemoryInvestmentLedger(),
        val checks: HoldingCheckRepository = InMemoryHoldingCheckRepository(ledger),
        val auditLog: InMemoryAuditRepository = InMemoryAuditRepository(),
    ) {
        var next: () -> ParsedCas = { CasFixtures.golden() }
        val clock: Clock = Clock.fixed(Instant.parse("2026-10-18T06:00:00Z"), ZoneOffset.UTC)
        val reconciliation = ReconciliationService(checks, ledger)
        val service = CasImportService({ _, _ -> next() }, accounts, instruments, ledger, checks, reconciliation, WealthFixtures.identifiers,
            WealthAudit(AuditService(auditLog, CoroutineScope(Dispatchers.Unconfined))), clock)
    }

    private val kit = Kit()
    private val user = UUID.randomUUID()
    private suspend fun import() = kit.service.import(user, "%PDF-".toByteArray(), CasFixtures.PASSWORD)

    @Test
    fun `a detailed statement becomes two reconciled holdings and eight ledger rows`() = runBlocking<Unit> {
        val result = import()
        assertEquals(8, result.batch.added); assertEquals(0, result.batch.duplicates); assertEquals(0, result.blocked)
        assertEquals(listOf("reconciled", "reconciled"), result.holdings.map { it.status.status.wire })
        assertEquals(listOf("••••4/56", "••••5678"), result.holdings.map { it.folioMasked })
        assertEquals(listOf("Example Mutual Fund", "Sample Mutual Fund"), result.holdings.map { it.amc })
        val rows = kit.ledger.active(user)
        assertEquals(8, rows.size)
        assertEquals(3, rows.count { it.type == InvestmentTxnType.STAMP_DUTY })
        assertEquals(Quantity.parse("160"), rows.mapNotNull { it.units }.fold(Quantity.ZERO) { a, q -> a + q })
        val flexi = kit.instruments.findByIds(rows.map { it.instrumentId }).first { it.isin == "INFZZ9000010" }
        assertEquals(MfPlan.DIRECT, flexi.mfPlan)
        assertEquals(Quantity.parse("150"), result.holdings.first().status.ledgerUnits)
    }

    @Test
    fun `folio numbers are stored encrypted and the same folio is one account across imports`() = runBlocking<Unit> {
        import(); import()
        val accounts = kit.accounts.findByUser(user)
        assertEquals(2, accounts.size)
        assertTrue(accounts.none { "90001234" in it.identifier.ciphertext || "80005678" in it.identifier.ciphertext })
        assertEquals("90001234/56", WealthFixtures.identifiers.reveal(accounts.first().identifier, "investment_accounts:${accounts.first().id}:identifier"))
    }

    @Test
    fun `importing the same file again adds nothing and stays reconciled`() = runBlocking<Unit> {
        import()
        val again = import()
        assertEquals(0, again.batch.added); assertEquals(8, again.batch.duplicates)
        assertEquals(8, kit.ledger.active(user).size)
        assertEquals(0, again.blocked)
        assertEquals(2, kit.service.review(user).size, "one status per holding, the latest statement counts")
    }

    @Test
    fun `closing units that don't match the rows block that holding only`() = runBlocking<Unit> {
        kit.next = { CasFixtures.withFlexi { it.copy(closingUnits = Quantity.parse("151")) } }
        val result = import()
        assertEquals(1, result.blocked)
        val blocked = result.holdings.first().status
        assertTrue(blocked.blocked)
        assertEquals("Units don't add up: the statement says 151 units, the transactions give 150", blocked.reason)
        assertFalse(result.holdings.last().status.blocked)
        assertEquals(blocked.check.schemeName, kit.service.review(user).first().status.check.schemeName, "blocked holdings come first in Review")
    }

    @Test
    fun `a statement that starts after the first purchase is blocked until the earlier history is imported`() = runBlocking<Unit> {
        // The later statement: 150 units brought forward, one more SIP of 10 units.
        kit.next = { CasFixtures.withFlexi { it.copy(openingUnits = Quantity.parse("150"), closingUnits = Quantity.parse("160"),
            rows = listOf(row("2026-01-10", "sip_buy", "1200.00", "10", "160"))) }.copy(statementFrom = LocalDate.of(2026, 1, 1), statementTo = LocalDate.of(2026, 3, 31)) }
        val later = import()
        val status = later.holdings.first().status
        assertTrue(status.blocked)
        assertTrue(status.reason.orEmpty().startsWith("Units don't add up: the history before 2026-01-01 is missing"), status.reason)

        kit.next = { CasFixtures.golden() }
        import()
        assertTrue(kit.service.review(user).none { it.status.blocked }, "with the history in, the later statement reconciles")
        assertEquals(Quantity.parse("160"), kit.service.review(user).first { it.status.check.statementTo == LocalDate.of(2026, 3, 31) }.status.ledgerUnits)
    }

    @Test
    fun `an unrecognised row that moves units blocks the holding but keeps the other rows`() = runBlocking<Unit> {
        kit.next = { CasFixtures.withFlexi { it.copy(rows = it.rows + row("2025-07-01", "unknown", null, "5", "155"), closingUnits = Quantity.parse("155")) } }
        val result = import()
        val flexi = result.holdings.first().status
        assertEquals(HoldingIssue.UNRECOGNISED_ROW, flexi.check.issue)
        assertEquals(HoldingIssue.UNRECOGNISED_ROW.text, flexi.reason)
        assertEquals(8, result.batch.added)
    }

    @Test
    fun `informational rows are kept as misc, and a misc row that moves units is not informational`() = runBlocking<Unit> {
        kit.next = { CasFixtures.withFlexi { it.copy(rows = it.rows + row("2025-07-01", "unknown", null, null) + row("2025-07-02", "misc", null, "0")) } }
        val ok = import()
        assertEquals(0, ok.blocked)
        assertEquals(2, kit.ledger.active(user).count { it.type == InvestmentTxnType.MISC })
        kit.next = { CasFixtures.withFlexi { it.copy(rows = it.rows + row("2025-07-03", "misc", null, "1", "151"), closingUnits = Quantity.parse("151")) } }
        assertEquals(HoldingIssue.UNRECOGNISED_ROW, import().holdings.first().status.check.issue)
    }

    @Test
    fun `a row that contradicts itself blocks the holding`() = runBlocking<Unit> {
        // A "purchase" that removes units can't be a ledger row.
        kit.next = { CasFixtures.withFlexi { it.copy(rows = it.rows + row("2025-08-01", "buy", "100.00", "-1", "149"), closingUnits = Quantity.parse("149")) } }
        val status = import().holdings.first().status
        assertEquals(HoldingIssue.INVALID_ROW, status.check.issue)
        assertTrue(status.blocked)
    }

    @Test
    fun `a running balance that doesn't follow the rows blocks the holding`() = runBlocking<Unit> {
        kit.next = { CasFixtures.withFlexi { s -> s.copy(rows = s.rows.mapIndexed { i, r -> if (i == 0) r.copy(balance = Quantity.parse("99")) else r }) } }
        assertEquals(HoldingIssue.BALANCE_BREAK, import().holdings.first().status.check.issue)
    }

    @Test
    fun `a scheme with no ISIN or AMFI code is blocked and writes no rows`() = runBlocking<Unit> {
        kit.next = { CasFixtures.withFlexi { it.copy(isin = null, amfiCode = null) } }
        val result = import()
        val status = result.holdings.first().status
        assertEquals(HoldingIssue.NOT_IDENTIFIED, status.check.issue)
        assertNull(status.check.instrumentId)
        assertEquals(1, result.batch.added, "only the liquid fund's row")
    }

    @Test
    fun `an undone import stops counting and its holdings leave Review`() = runBlocking<Unit> {
        val result = import()
        assertFalse(kit.service.undo(UUID.randomUUID(), result.batch.id), "someone else can't undo it")
        assertTrue(kit.service.undo(user, result.batch.id))
        assertFalse(kit.service.undo(user, result.batch.id), "only once")
        assertTrue(kit.ledger.active(user).isEmpty())
        assertTrue(kit.service.review(user).isEmpty())
        assertEquals(ImportStatus.UNDONE, kit.service.batches(user).single().status)
        assertEquals(8, import().batch.added, "the same file can be imported again")
    }

    @Test
    fun `a file the parser refuses writes nothing and is audited by code`() = runBlocking<Unit> {
        kit.next = { throw CasParseException("PASSWORD_INCORRECT", "That password doesn't open this statement", clientError = true) }
        assertEquals("PASSWORD_INCORRECT", assertFailsWith<CasParseException> { import() }.code)
        assertTrue(kit.accounts.findByUser(user).isEmpty())
        assertTrue(kit.service.batches(user).isEmpty())
        val audit = kit.auditLog.findByUserId(user).single()
        assertEquals(WealthAuditActions.IMPORT_REJECTED, audit.action)
        assertEquals("password_incorrect", audit.metadata?.get("reason")?.jsonPrimitive?.content)
    }

    @Test
    fun `an import is audited with counts only`() = runBlocking<Unit> {
        val result = import()
        val audit = kit.auditLog.findByUserId(user).single()
        assertEquals(WealthAuditActions.IMPORT, audit.action); assertEquals(result.batch.id, audit.entityId)
        assertEquals("8", audit.metadata?.get("added")?.jsonPrimitive?.content)
        assertEquals("casparser", audit.metadata?.get("reader")?.jsonPrimitive?.content)
        assertFalse(audit.metadata.toString().contains("90001234") || audit.metadata.toString().contains("Flexi"))
        kit.service.undo(user, result.batch.id)
        assertEquals(WealthAuditActions.IMPORT_UNDO, kit.auditLog.findByUserId(user).first().action)
    }

    @Test
    fun `the statement date falls back to the valuation date, and none at all is refused`() = runBlocking<Unit> {
        kit.next = { CasFixtures.golden().copy(statementTo = null) }
        assertEquals(LocalDate.of(2025, 12, 31), import().batch.statementTo)
        kit.next = { CasFixtures.golden().let { g -> g.copy(statementTo = null, folios = g.folios.map { f -> CasFolio(f.folioNumber, f.amc, f.schemes.map { it.copy(valuationDate = null) }) }) } }
        assertEquals("NO_STATEMENT_DATE", assertFailsWith<CasParseException> { import() }.code)
    }

    @Test
    fun `a hostile folio number is refused before anything is stored`() = runBlocking<Unit> {
        // The second folio is hostile: not even the first folio's account may be written.
        kit.next = { CasFixtures.golden().let { g -> g.copy(folios = listOf(g.folios.first(), CasFolio("1234<script>", "Example Mutual Fund", g.folios.first().schemes))) } }
        assertEquals("INVALID_FOLIO", assertFailsWith<CasParseException> { import() }.code)
        assertTrue(kit.accounts.findByUser(user).isEmpty())
        assertTrue(kit.ledger.active(user).isEmpty())
    }

    @Test
    fun `asset classes come from the SEBI category, then the tax type`() {
        val base = CasFixtures.golden().folios.first().schemes.first()
        fun of(category: String?, type: String? = null) = CasImportService.assetClassOf(base.copy(category = category, schemeType = type))
        assertEquals(AssetClass.EQUITY_IN, of("Equity Scheme - Flexi Cap Fund"))
        assertEquals(AssetClass.DEBT, of("Debt Scheme - Liquid Fund"))
        assertEquals(AssetClass.HYBRID, of("Hybrid Scheme - Arbitrage Fund", "EQUITY"))
        assertEquals(AssetClass.GOLD, of("Other Scheme - Gold ETF"))
        assertEquals(AssetClass.EQUITY_FOREIGN, of("Other Scheme - FoF Overseas"))
        assertEquals(AssetClass.EQUITY_IN, of(null, "EQUITY"))
        assertEquals(AssetClass.DEBT, of("Other Scheme - Index Funds", "DEBT"))
        assertEquals(AssetClass.OTHER, of(null, null))
    }
}
