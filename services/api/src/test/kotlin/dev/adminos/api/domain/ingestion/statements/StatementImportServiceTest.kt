package dev.adminos.api.domain.ingestion.statements

import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestSource
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventService
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StatementImportServiceTest {

    private val repo = InMemoryIngestEventRepository()
    private val userId = UUID.randomUUID()
    private var next: ParsedStatement = statement(emptyList())
    private var lastPassword: String? = null
    private val parser = object : StatementParser {
        override suspend fun parse(file: ByteArray, filename: String, password: String?): ParsedStatement {
            lastPassword = password
            return next
        }
    }
    private val service = StatementImportService(parser, IngestEventService(repo))

    private fun row(day: Int, debit: String? = null, credit: String? = null, desc: String = "UPI-SWIGGY-swiggy@icici", bal: String? = null) =
        StatementRow(LocalDate.of(2026, 10, day), desc, debit?.let(::BigDecimal), credit?.let(::BigDecimal), bal?.let(::BigDecimal), null)

    private fun statement(rows: List<StatementRow>) = ParsedStatement("hdfc", "1234", rows.minOfOrNull { it.date }, rows.maxOfOrNull { it.date }, rows, 0, emptyList())

    private suspend fun import() = service.import(userId, byteArrayOf(1), "x.csv", "pw")

    @Test
    fun `rows become statement events with direction, amount, vpa and midday IST dates`() = runBlocking<Unit> {
        next = statement(listOf(row(1, debit = "249.00"), row(2, credit = "100000.00", desc = "NEFT CR-ACME SALARY")))
        val r = import()
        assertEquals(2, r.accepted); assertEquals("hdfc", r.bankCode); assertEquals("pw", lastPassword)
        val events = repo.findByUser(userId).sortedBy { it.occurredAt }
        assertEquals(listOf(IngestDirection.DEBIT, IngestDirection.CREDIT), events.map { it.direction })
        assertEquals(IngestSource.STATEMENT, events[0].source)
        assertEquals("swiggy@icici", events[0].upiVpa); assertEquals("1234", events[0].accountHint)
        assertEquals(LocalDate.of(2026, 10, 1), events[0].occurredAt.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate())
    }

    @Test
    fun `identical lines in one statement are kept, re-uploading adds nothing`() = runBlocking<Unit> {
        next = statement(listOf(row(3, debit = "10.00", desc = "TEA STALL"), row(3, debit = "10.00", desc = "TEA STALL")))
        assertEquals(2, import().accepted, "two real ₹10 teas on the same day")
        val again = import()
        assertEquals(0, again.accepted); assertEquals(2, again.duplicates)
    }

    @Test
    fun `an overlapping statement only adds the new rows`() = runBlocking<Unit> {
        next = statement(listOf(row(1, debit = "249.00", bal = "100.00"), row(2, debit = "50.00", bal = "50.00")))
        import()
        next = statement(listOf(row(2, debit = "50.00", bal = "50.00"), row(3, credit = "500.00", bal = "550.00")))
        val r = import()
        assertEquals(1, r.accepted); assertEquals(1, r.duplicates)
        assertEquals(3, repo.findByUser(userId).size)
    }

    @Test
    fun `parser failures propagate with their code`() = runBlocking<Unit> {
        val failing = StatementImportService(object : StatementParser {
            override suspend fun parse(file: ByteArray, filename: String, password: String?): ParsedStatement =
                throw StatementParseException("PASSWORD_REQUIRED", "This statement is password-protected", true)
        }, IngestEventService(repo))
        val e = assertFailsWith<StatementParseException> { failing.import(userId, byteArrayOf(1), "x.pdf", null) }
        assertEquals("PASSWORD_REQUIRED", e.code)
    }
}
