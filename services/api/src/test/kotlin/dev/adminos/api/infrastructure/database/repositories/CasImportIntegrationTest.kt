package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.wealth.Quantity
import dev.adminos.api.domain.wealth.WealthAudit
import dev.adminos.api.domain.wealth.WealthFixtures
import dev.adminos.api.domain.wealth.imports.CasFixtures
import dev.adminos.api.domain.wealth.imports.CasImportService
import dev.adminos.api.domain.wealth.imports.CasParseException
import dev.adminos.api.domain.wealth.imports.ReconciliationService
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.statements.HttpCasReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * AOS-805 end to end: the committed synthetic CAS PDF → the real Python parser (built from its Dockerfile, casparser
 * pinned) → the import service → Postgres (encrypted folio, ledger, holding checks) → reconciliation.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CasImportIntegrationTest {

    private val parserContainer = GenericContainer(
        ImageFromDockerfile("adminos-statement-parser-test", false).withFileFromPath(".", Paths.get("../pdf-parser"))
    ).withExposedPorts(8000).waitingFor(Wait.forHttp("/health"))

    private lateinit var service: CasImportService
    private val ledger = ExposedInvestmentLedger()

    @BeforeAll
    fun start() {
        PostgresTestDatabase.ensureStarted()
        parserContainer.start()
        val checks = ExposedHoldingCheckRepository()
        val instruments = ExposedInstrumentRepository()
        val audit = WealthAudit(AuditService(ExposedAuditRepository(), CoroutineScope(Dispatchers.IO)))
        service = CasImportService(
            HttpCasReader("http://${parserContainer.host}:${parserContainer.getMappedPort(8000)}"),
            ExposedInvestmentAccountRepository(), instruments, ledger, checks, ReconciliationService(checks, ledger),
            WealthFixtures.identifiers, audit,
            dev.adminos.api.domain.wealth.prices.PriceService({ error("no AMFI fetch here") }, instruments, ExposedPriceRepository(), audit)
        )
    }

    @AfterAll
    fun stop() = parserContainer.stop()

    @Test
    fun `a synthetic CAS imports, reconciles, re-imports as duplicates and can be undone`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val first = service.import(user, CasFixtures.goldenPdf(), CasFixtures.PASSWORD)
        assertEquals(8, first.batch.added)
        assertEquals(listOf("reconciled", "reconciled"), first.holdings.map { it.status.status.wire })
        assertEquals(Quantity.parse("150"), first.holdings.first().status.ledgerUnits)

        val again = service.import(user, CasFixtures.goldenPdf(), CasFixtures.PASSWORD)
        assertEquals(0, again.batch.added); assertEquals(8, again.batch.duplicates)
        assertEquals(2, service.review(user).size)

        assertTrue(service.undo(user, again.batch.id))
        assertEquals(2, service.review(user).size, "the first import's checks still stand")
        assertTrue(service.undo(user, first.batch.id))
        assertTrue(service.review(user).isEmpty())
        assertTrue(ledger.active(user).isEmpty())
    }

    @Test
    fun `the wrong password is refused by the real parser and nothing is written`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        assertEquals("PASSWORD_INCORRECT", assertFailsWith<CasParseException> { service.import(user, CasFixtures.goldenPdf(), CasFixtures.WRONG) }.code)
        assertEquals("PASSWORD_REQUIRED", assertFailsWith<CasParseException> { service.import(user, CasFixtures.goldenPdf(), null) }.code)
        assertTrue(service.batches(user).isEmpty())
    }
}
