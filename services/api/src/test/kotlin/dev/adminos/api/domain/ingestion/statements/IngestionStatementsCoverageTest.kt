package dev.adminos.api.domain.ingestion.statements

import dev.adminos.api.InfraIngestTestKit.assertRejectsMissingFields
import dev.adminos.api.InfraIngestTestKit.assertRoundTrips
import dev.adminos.api.InfraIngestTestKit.routeTest
import dev.adminos.api.InfraIngestTestKit.tokenFor
import dev.adminos.api.InfraIngestTestKit.user
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEventService
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Statement import mapping and the upload route's edge cases (AOS-312). */
class IngestionStatementsCoverageTest {

    private val day = LocalDate.of(2026, 10, 1)
    private fun row(description: String, debit: String? = "100.00", credit: String? = null, balance: String? = null, reference: String? = null) =
        StatementRow(day, description, debit?.let(::BigDecimal), credit?.let(::BigDecimal), balance?.let(::BigDecimal), reference)

    private var parsed = ParsedStatement(null, null, null, null, emptyList(), 0, emptyList())
    private var seen: Triple<Int, String, String?>? = null
    private val parser = object : StatementParser {
        override suspend fun parse(file: ByteArray, filename: String, password: String?): ParsedStatement {
            seen = Triple(file.size, filename, password); return parsed
        }
    }
    private val events = InMemoryIngestEventRepository()
    private val service = StatementImportService(parser, IngestEventService(events))
    private val userId = UUID.randomUUID()

    private fun import(vararg rows: StatementRow, bank: String? = "hdfc"): StatementImportResult = runBlocking {
        parsed = ParsedStatement(bank, "1234", null, null, rows.toList(), 0, emptyList())
        service.import(userId, byteArrayOf(1), "s.csv", null)
    }

    @Test
    fun `rows with both or neither side, or a zero amount, are skipped`() {
        val r = import(row("BOTH", debit = "1.00", credit = "1.00"), row("NEITHER", debit = null), row("ZERO", debit = "0.00"), row("OK"))
        assertEquals(1, r.rows); assertEquals(3, r.skippedRows)
    }

    @Test
    fun `a credit row becomes a credit event`() = runBlocking<Unit> {
        import(row("NEFT CR-ACME SALARY", debit = null, credit = "50000.00"))
        val e = events.findByUser(userId).single()
        assertEquals(IngestDirection.CREDIT, e.direction)
        assertEquals(PaymentMethod.NETBANKING, e.paymentMethod)
    }

    @Test
    fun `the rail is read from the narration`() = runBlocking<Unit> {
        import(row("UPI-RAMU-ramu@ybl-1"), row("POS 512345XXXXXX1234 STARBUCKS"), row("ATM WDL MG ROAD"), row("CHQ DEP 000123"))
        val byText = events.findByUser(userId).associate { it.merchantRaw to it.paymentMethod }
        assertEquals(PaymentMethod.UPI, byText["UPI-RAMU-ramu@ybl-1"])
        assertEquals(PaymentMethod.CARD_DEBIT, byText["POS 512345XXXXXX1234 STARBUCKS"])
        assertEquals(PaymentMethod.CASH, byText["ATM WDL MG ROAD"])
        assertNull(byText["CHQ DEP 000123"])
    }

    @Test
    fun `the VPA in a narration is kept in lowercase`() = runBlocking<Unit> {
        import(row("UPI-RAMU CHAI-Ramu.Chai@YBL-PAYMENT"))
        assertEquals("ramu.chai@ybl", events.findByUser(userId).single().upiVpa)
    }

    @Test
    fun `an exact 12-digit reference column wins over the narration`() = runBlocking<Unit> {
        import(row("UPI-SWIGGY-111111111111", reference = " 222222222222 "))
        assertEquals("222222222222", events.findByUser(userId).single().paymentRef)
    }

    @Test
    fun `a non-RRN reference column falls back to the RRN in the narration`() = runBlocking<Unit> {
        import(row("UPI-SWIGGY-111111111111-PAYMENT", reference = "CHQ123"))
        assertEquals("111111111111", events.findByUser(userId).single().paymentRef)
    }

    @Test
    fun `without bank or balance there is no metadata`() = runBlocking<Unit> {
        import(row("CHQ DEP"), bank = null)
        assertNull(events.findByUser(userId).single().metadata)
    }

    @Test
    fun `bank and balance are kept as metadata`() = runBlocking<Unit> {
        import(row("CHQ DEP", balance = "900.00"))
        assertEquals(mapOf("bank" to "hdfc", "balance" to BigDecimal("900.00")), events.findByUser(userId).single().metadata)
    }

    @Test
    fun `statement import response round-trips`() {
        assertRoundTrips(StatementImportResponse.serializer(),
            """{"bankCode":null,"accountLast4":null,"periodStart":null,"periodEnd":null,"rows":0,"accepted":0,"duplicates":0,"skippedRows":0,"warnings":[]}""")
        assertRejectsMissingFields(StatementImportResponse.serializer())
    }

    // ── Upload route ──

    private val owner = user()
    private fun uploadTest(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = routeTest({ statementRoutes(service) }, block)

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.upload(parts: FormBuilder.() -> Unit) =
        client.post("/api/v1/statements") { bearerAuth(tokenFor(owner)); setBody(MultiPartFormDataContent(formData(parts))) }

    private fun FormBuilder.file(name: String = "file", filename: String = "hdfc_oct.pdf", bytes: ByteArray = byteArrayOf(1, 2, 3)) {
        val headers = Headers.build {
            append(HttpHeaders.ContentType, "application/pdf")
            append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
        }
        append(name, bytes, headers)
    }

    @Test
    fun `a file under another field name is refused (AOS-618)`() = uploadTest {
        val res = upload { file(name = "attachment") }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue("UPLOAD_001" in res.bodyAsText())
    }

    @Test
    fun `no file at all is STATEMENT_001`() = uploadTest {
        val res = upload { append("password", "") }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue("STATEMENT_001" in res.bodyAsText())
    }

    @Test
    fun `a second file or a repeated field is refused (AOS-618)`() = uploadTest {
        assertEquals(HttpStatusCode.BadRequest, upload { file(); file() }.status)
        assertEquals(HttpStatusCode.BadRequest, upload { file(); append("password", "a"); append("password", "b") }.status)
    }

    @Test
    fun `a password field longer than any real statement password is refused`() = uploadTest {
        assertEquals(HttpStatusCode.BadRequest, upload { file(); append("password", "x".repeat(257)) }.status)
    }

    @Test
    fun `a file with an empty filename is still imported`() = uploadTest {
        val res = upload { file(filename = "") }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals("", seen!!.second)
    }

    @Test
    fun `an empty password counts as no password`() = uploadTest {
        val res = upload { file(); append("password", "") }
        assertEquals(HttpStatusCode.OK, res.status)
        assertNull(seen!!.third)
    }

    @Test
    fun `an unrelated form field is refused (AOS-618)`() = uploadTest {
        val res = upload { file(); append("note", "october") }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue("UPLOAD_001" in res.bodyAsText())
    }

    @Test
    fun `a statement well over 20 MB is a 413 too, not a 500 (found in AOS-507)`() = uploadTest {
        val res = upload { file(bytes = ByteArray(20 * 1024 * 1024 + 4096)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, res.status)
        assertTrue("FILE_TOO_LARGE" in res.bodyAsText())
    }

    @Test
    fun `a statement over 20 MB is a 413`() = uploadTest {
        val res = upload { file(bytes = ByteArray(20 * 1024 * 1024 + 1)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, res.status)
        assertTrue("FILE_TOO_LARGE" in res.bodyAsText())
    }

    @Test
    fun `a statement without a period reports null dates`() = uploadTest {
        val data = Json.parseToJsonElement(upload { file() }.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals(JsonNull, data["periodStart"]); assertEquals(JsonNull, data["periodEnd"])
    }

    @Test
    fun `a statement with a period reports its dates`() = uploadTest {
        parsed = ParsedStatement("sbi", "5678", day, day.plusDays(30), emptyList(), 0, emptyList())
        val body = upload { file() }.bodyAsText()
        assertTrue("\"periodStart\":\"2026-10-01\"" in body && "\"periodEnd\":\"2026-10-31\"" in body, body)
    }
}
