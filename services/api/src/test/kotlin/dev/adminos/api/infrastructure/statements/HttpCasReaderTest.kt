package dev.adminos.api.infrastructure.statements

import com.sun.net.httpserver.HttpServer
import dev.adminos.api.domain.wealth.MfPlan
import dev.adminos.api.domain.wealth.Quantity
import dev.adminos.api.domain.wealth.UnitPrice
import dev.adminos.api.domain.wealth.imports.CasFixtures
import dev.adminos.api.domain.wealth.imports.CasParseException
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-805: the API side of the CAS contract, decoded from the parser's own committed golden reply. */
class HttpCasReaderTest {

    private fun server(status: Int, body: String, onRequest: (String) -> Unit = {}): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/cas:parse") { ex ->
                onRequest(String(ex.requestBody.readAllBytes(), Charsets.ISO_8859_1))
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun HttpServer.url() = "http://127.0.0.1:${address.port}"

    @Test
    fun `the parser's golden reply decodes into exact types with the folio kept apart`() = runBlocking<Unit> {
        var request = ""
        val s = server(200, CasFixtures.goldenJson()) { request = it }
        try {
            val cas = HttpCasReader(s.url()).read("%PDF-1.4".toByteArray(), CasFixtures.PASSWORD)
            assertEquals("casparser", cas.readerId); assertEquals("cams", cas.issuer)
            assertEquals(LocalDate.of(2025, 12, 31), cas.statementTo)
            assertEquals(listOf("90001234 / 56", "80005678"), cas.folios.map { it.folioNumber })
            assertFalse("90001234" in cas.folios.first().toString(), "a folio never prints")
            val flexi = cas.folios.first().schemes.single()
            assertEquals("INFZZ9000010", flexi.isin); assertEquals(MfPlan.DIRECT, flexi.plan)
            assertEquals(Quantity.parse("150"), flexi.closingUnits)
            assertEquals(UnitPrice.parse("120"), flexi.nav)
            assertEquals(BigDecimal("18000.00"), flexi.value)
            assertEquals(listOf("buy", "stamp_duty", "sip_buy", "stamp_duty", "sip_buy", "stamp_duty", "sell"), flexi.rows.map { it.kind })
            assertEquals(Quantity.parse("-50"), flexi.rows.last().units)
            assertTrue("filename=\"statement.pdf\"" in request && CasFixtures.PASSWORD in request)
        } finally { s.stop(0) }
    }

    @Test
    fun `parser errors keep their codes and an unreachable parser is a server error`() = runBlocking<Unit> {
        val s = server(422, """{"error":{"code":"PASSWORD_INCORRECT","message":"That password doesn't open this statement"}}""")
        try {
            val e = assertFailsWith<CasParseException> { HttpCasReader(s.url()).read(byteArrayOf(1), "bad") }
            assertEquals("PASSWORD_INCORRECT", e.code); assertTrue(e.clientError)
        } finally { s.stop(0) }
        val weird = server(422, """{"error":{"code":"<script>","message":"x"}}""")
        try {
            assertEquals("UNREADABLE", assertFailsWith<CasParseException> { HttpCasReader(weird.url()).read(byteArrayOf(1), "p") }.code)
        } finally { weird.stop(0) }
        val broken = server(500, "oops")
        try {
            val e = assertFailsWith<CasParseException> { HttpCasReader(broken.url()).read(byteArrayOf(1), "p") }
            assertEquals("PARSER_ERROR", e.code); assertFalse(e.clientError)
        } finally { broken.stop(0) }
        assertEquals("PARSER_UNAVAILABLE", assertFailsWith<CasParseException> { HttpCasReader("http://127.0.0.1:1").read(byteArrayOf(1), null) }.code)
    }

    @Test
    fun `a reply that breaks the contract is refused, never stored as data`() {
        val reader = HttpCasReader("http://unused")
        val golden = CasFixtures.goldenJson()
        val edits = listOf(
            "\"closingUnits\": \"150.000\"" to "\"closingUnits\": \"1.5e2\"",
            "\"amount\": \"5500.00\"" to "\"amount\": \"-5500\"",
            "\"nav\": \"120.0000\"" to "\"nav\": \"0\"",
            "\"statementTo\": \"2025-12-31\"" to "\"statementTo\": \"31/12/2025\"",
            "\"plan\": \"direct\"" to "\"plan\": \"secret\"",
            // A folio whose identifier is missing from the identifiers block.
            "\"ref\": \"f2\",\n        \"value\"" to "\"ref\": \"f9\",\n        \"value\"",
            "\"kind\": \"sell\"" to "\"kind\": \"sell; DROP\"",
        )
        for ((from, to) in edits) {
            assertTrue(from in golden, "the golden reply has $from")
            assertEquals("PARSER_CONTRACT", assertFailsWith<CasParseException>(from) { reader.decode(golden.replace(from, to)) }.code)
        }
        for (body in listOf("{\"not\": \"a cas\"}", "not json")) {
            assertEquals("PARSER_CONTRACT", assertFailsWith<CasParseException> { reader.decode(body) }.code)
        }
    }
}
