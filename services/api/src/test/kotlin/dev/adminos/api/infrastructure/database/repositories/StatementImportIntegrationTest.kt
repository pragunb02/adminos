package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.pipeline.*
import dev.adminos.api.domain.ingestion.statements.StatementImportService
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.statements.HttpStatementParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import java.math.BigDecimal
import java.nio.file.Paths
import kotlin.test.assertEquals

/** Kotlin API ↔ real Python statement parser (built from its Dockerfile) ↔ Postgres ↔ pipeline (AOS-202). */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatementImportIntegrationTest {

    private val parserContainer = GenericContainer(
        ImageFromDockerfile("adminos-statement-parser-test", false).withFileFromPath(".", Paths.get("../pdf-parser"))
    ).withExposedPorts(8000).waitingFor(Wait.forHttp("/health"))

    @BeforeAll
    fun start() {
        PostgresTestDatabase.ensureStarted()
        parserContainer.start()
    }

    @AfterAll
    fun stop() = parserContainer.stop()

    /** SYNTHETIC HDFC-style export. */
    private val csv = """
        HDFC BANK Ltd.
        Account No : XXXXXXXX4321
        Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
        01/10/26,UPI-SWIGGY-swiggy@icici-ICIC0000001-123456789012,R1,01/10/26,249.00,,49751.00
        02/10/26,NEFT CR-ACME CORP-SALARY OCT,R2,02/10/26,,"1,00,000.00","1,49,751.00"
        03/10/26,POS 1234XXXX AMAZON PAY IN,R3,03/10/26,"1,299.50",,"1,48,451.50"
    """.trimIndent().toByteArray()

    @Test
    fun `statement upload becomes categorised transactions, and re-upload adds nothing`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val parser = HttpStatementParser("http://${parserContainer.host}:${parserContainer.getMappedPort(8000)}")
        val events = ExposedIngestEventRepository()
        val transactions = ExposedTransactionRepository()
        val service = StatementImportService(parser, IngestEventService(events))

        val first = service.import(userId, csv, "Ravi_HDFC.csv", null)
        assertEquals("hdfc", first.bankCode); assertEquals("4321", first.accountLast4)
        assertEquals(3, first.accepted)

        val processor = IngestProcessor(events, IngestPipeline(listOf(ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()), CategorizeStep(CategorizationService()))),
            RepositoryOutcomeWriter(transactions, events) { work -> dbQuery { work() } })
        while (processor.processBatch() > 0) { }

        val txns = transactions.findByUserId(userId, limit = 10).sortedBy { it.transactedAt }
        assertEquals(listOf(TransactionType.DEBIT, TransactionType.CREDIT, TransactionType.DEBIT), txns.map { it.type })
        assertEquals(0, BigDecimal("100000.00").compareTo(txns[1].amount))
        assertEquals("Swiggy", txns[0].merchantName); assertEquals(TransactionCategory.FOOD, txns[0].category)
        assertEquals("swiggy@icici", txns[0].upiVpa); assertEquals("statement", txns[0].sourceType)

        val again = service.import(userId, csv, "Ravi_HDFC.csv", null)
        assertEquals(0, again.accepted); assertEquals(3, again.duplicates)
    }
}
