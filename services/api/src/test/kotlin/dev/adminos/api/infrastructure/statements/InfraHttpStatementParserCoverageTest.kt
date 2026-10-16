package dev.adminos.api.infrastructure.statements

import com.sun.net.httpserver.HttpServer
import dev.adminos.api.InfraIngestTestKit.assertRejectsMissingFields
import dev.adminos.api.InfraIngestTestKit.assertRoundTrips
import dev.adminos.api.InfraIngestTestKit.privateSerializer
import dev.adminos.api.domain.ingestion.statements.StatementParseException
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HttpStatementParser status mapping and wire format against a local JDK HttpServer (AOS-312). */
class InfraHttpStatementParserCoverageTest {

    private fun <T> withServer(status: Int, body: String, block: suspend (String) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/statements:parse") { ex ->
                ex.requestBody.readAllBytes()
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        return try { runBlocking { block("http://127.0.0.1:${server.address.port}") } } finally { server.stop(0) }
    }

    @Test
    fun `413 with the parser's error body is a client error with its code`() {
        val e = withServer(413, """{"error":{"code":"FILE_TOO_LARGE","message":"Statement exceeds 20 MB"}}""") { url ->
            assertFailsWith<StatementParseException> { HttpStatementParser(url).parse(byteArrayOf(1), "hdfc.pdf", null) }
        }
        assertEquals("FILE_TOO_LARGE", e.code)
        assertTrue(e.clientError)
    }

    @Test
    fun `422 with an unreadable error body falls back to UNREADABLE`() {
        val e = withServer(422, "<html>oops</html>") { url ->
            assertFailsWith<StatementParseException> { HttpStatementParser(url).parse(byteArrayOf(1), "sbi.xlsx", "") }
        }
        assertEquals("UNREADABLE", e.code)
        assertEquals("The statement couldn't be read", e.message)
        assertTrue(e.clientError)
    }

    @Test
    fun `a 5xx from the parser is a server-side PARSER_ERROR`() {
        val e = withServer(500, "boom") { url ->
            assertFailsWith<StatementParseException> { HttpStatementParser(url).parse(byteArrayOf(1), "statement", null) }
        }
        assertEquals("PARSER_ERROR", e.code)
        assertTrue("500" in e.message)
        assertFalse(e.clientError)
    }

    @Test
    fun `optional fields of the parsed statement may be absent`() {
        val parsed = withServer(200, """{"periodStart":"2026-10-01","rows":[{"date":"2026-10-02","description":"NEFT CR-ACME","credit":"50000.00"}]}""") { url ->
            HttpStatementParser(url).parse(byteArrayOf(1), "icici.csv", null)
        }
        assertNull(parsed.bankCode); assertNull(parsed.accountLast4); assertNull(parsed.periodEnd)
        assertEquals(LocalDate.of(2026, 10, 1), parsed.periodStart)
        val row = parsed.rows.single()
        assertEquals(BigDecimal("50000.00"), row.credit)
        assertNull(row.debit); assertNull(row.balance); assertNull(row.reference)
        assertEquals(0, parsed.skippedRows)
        assertTrue(parsed.warnings.isEmpty())
    }

    private val wire = "dev.adminos.api.infrastructure.statements.HttpStatementParser"

    @Test
    fun `wire row round-trips with and without its optional fields`() {
        assertRoundTrips(privateSerializer("$wire\$WireRow"),
            """{"date":"2026-10-01","description":"UPI-SWIGGY","debit":"249.00","credit":"1.00","balance":"49751.00","reference":"123456789012"}""",
            """{"date":"2026-10-01","description":"UPI-SWIGGY"}""")
        assertRejectsMissingFields(privateSerializer("$wire\$WireRow"))
    }

    @Test
    fun `wire result round-trips with and without its optional fields`() {
        assertRoundTrips(privateSerializer("$wire\$WireResult"),
            """{"bankCode":"hdfc","accountLast4":"1234","periodStart":"2026-10-01","periodEnd":"2026-10-31","rows":[],"skippedRows":2,"warnings":["2 rows skipped"]}""",
            """{"rows":[]}""")
        assertRejectsMissingFields(privateSerializer("$wire\$WireResult"))
    }

    @Test
    fun `wire error round-trips and requires code and message`() {
        assertRoundTrips(privateSerializer("$wire\$WireError"), """{"error":{"code":"PASSWORD_REQUIRED","message":"This statement is password protected"}}""")
        assertRejectsMissingFields(privateSerializer("$wire\$WireError"))
        assertRejectsMissingFields(privateSerializer("$wire\$WireErrorBody"))
    }
}
