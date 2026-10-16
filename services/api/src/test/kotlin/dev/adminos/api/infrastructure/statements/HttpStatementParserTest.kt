package dev.adminos.api.infrastructure.statements

import com.sun.net.httpserver.HttpServer
import dev.adminos.api.domain.ingestion.statements.StatementParseException
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpStatementParserTest {

    private fun server(status: Int, body: String, onRequest: (String) -> Unit = {}): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/statements:parse") { ex ->
                onRequest(String(ex.requestBody.readAllBytes(), Charsets.ISO_8859_1))
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun HttpServer.url() = "http://127.0.0.1:${address.port}"

    @Test
    fun `sends a generic filename and the password, and maps the response`() = runBlocking<Unit> {
        var request = ""
        val s = server(200, """{"bankCode":"hdfc","accountLast4":"1234","periodStart":"2026-10-01","periodEnd":"2026-10-03",
            "rows":[{"date":"2026-10-01","description":"UPI-SWIGGY","debit":"249.00","credit":null,"balance":"49751.00","reference":"R1"}],
            "skippedRows":1,"warnings":[]}""") { request = it }
        try {
            val r = HttpStatementParser(s.url()).parse("a,b".toByteArray(), "Ravi_Kumar_HDFC_Oct.CSV", "01011990")
            assertEquals("hdfc", r.bankCode); assertEquals(BigDecimal("249.00"), r.rows.single().debit)
            assertTrue("filename=\"statement.csv\"" in request, "generic filename, extension kept")
            assertTrue("Ravi" !in request, "the user's real file name must not be sent")
            assertTrue("name=\"password\"" in request && "01011990" in request)
        } finally { s.stop(0) }
    }

    @Test
    fun `no password part when none given`() = runBlocking<Unit> {
        var request = ""
        val s = server(200, """{"rows":[],"skippedRows":0,"warnings":[]}""") { request = it }
        try {
            HttpStatementParser(s.url()).parse(byteArrayOf(1), "x.pdf", null)
            assertTrue("name=\"password\"" !in request)
        } finally { s.stop(0) }
    }

    @Test
    fun `error codes and unreachable parser`() = runBlocking<Unit> {
        val s = server(422, """{"error":{"code":"PASSWORD_INCORRECT","message":"The statement password is incorrect"}}""")
        try {
            val e = assertFailsWith<StatementParseException> { HttpStatementParser(s.url()).parse(byteArrayOf(1), "x.pdf", "bad") }
            assertEquals("PASSWORD_INCORRECT", e.code); assertTrue(e.clientError)
        } finally { s.stop(0) }
        val down = assertFailsWith<StatementParseException> { HttpStatementParser("http://127.0.0.1:1").parse(byteArrayOf(1), "x.pdf", null) }
        assertEquals("PARSER_UNAVAILABLE", down.code)
    }
}
