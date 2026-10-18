package dev.adminos.api.domain.ingestion

import dev.adminos.api.InfraIngestTestKit.assertRejectsMissingFields
import dev.adminos.api.domain.ingestion.connection.randomTokenEncryptor
import dev.adminos.api.InfraIngestTestKit.assertRoundTrips
import dev.adminos.api.InfraIngestTestKit.routeTest
import dev.adminos.api.InfraIngestTestKit.tokenFor
import dev.adminos.api.InfraIngestTestKit.user
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.InMemoryConnectionRepository
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.sync.InMemorySyncSessionRepository
import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.domain.ingestion.sync.SyncSessionStatus
import dev.adminos.api.domain.ingestion.sync.SyncType
import dev.adminos.api.domain.ingestion.sync.ingestionRoutes
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Structured SMS batches (SmsRecordAdapter, IngestionService, /ingest/sms and /sync routes) (AOS-312). */
class IngestionSmsBatchCoverageTest {

    private val userId = UUID.randomUUID()
    private fun record(amount: Double = 249.0, date: String = "2026-10-01T09:12:00+05:30", direction: String = "debit", method: String? = null) =
        SmsRecord(merchant = "SWIGGY", amount = amount, date = date, accountLast4 = "1234", paymentMethod = method, direction = direction)

    // ── SmsRecordAdapter ──

    @Test
    fun `an offset date-time is used as is`() {
        assertEquals(Instant.parse("2026-10-01T03:42:00Z"), SmsRecordAdapter.toEvent(userId, record(), "batch[0]").occurredAt)
    }

    @Test
    fun `a local date-time is read in India time`() {
        assertEquals(Instant.parse("2026-10-01T03:42:00Z"), SmsRecordAdapter.toEvent(userId, record(date = "2026-10-01T09:12:00"), "batch[0]").occurredAt)
    }

    @Test
    fun `a date alone is the start of that day in India time`() {
        assertEquals(Instant.parse("2026-09-30T18:30:00Z"), SmsRecordAdapter.toEvent(userId, record(date = "2026-10-01"), "batch[0]").occurredAt)
    }

    @Test
    fun `an unreadable date is rejected naming the field`() {
        val e = assertFailsWith<IllegalArgumentException> { SmsRecordAdapter.toEvent(userId, record(date = "01/10/26"), "batch[3]") }
        assertTrue(e.message!!.startsWith("batch[3].date"), e.message)
    }

    @Test
    fun `more than two decimal places is rejected`() {
        val e = assertFailsWith<IllegalArgumentException> { SmsRecordAdapter.toEvent(userId, record(amount = 249.123), "batch[0]") }
        assertTrue("2 decimal places" in e.message!!)
    }

    @Test
    fun `credit direction and payment method are read case-insensitively`() {
        val e = SmsRecordAdapter.toEvent(userId, record(direction = "CREDIT", method = "Upi"), "batch[0]")
        assertEquals(IngestDirection.CREDIT, e.direction)
        assertEquals(PaymentMethod.UPI, e.paymentMethod)
        assertEquals(BigDecimal("249.00"), e.amount)
    }

    @Test
    fun `an unknown direction is rejected`() {
        val e = assertFailsWith<IllegalArgumentException> { SmsRecordAdapter.toEvent(userId, record(direction = "sideways"), "batch[1]") }
        assertTrue(e.message!!.startsWith("batch[1].direction"), e.message)
    }

    @Test
    fun `an unknown payment method is rejected listing the allowed ones`() {
        val e = assertFailsWith<IllegalArgumentException> { SmsRecordAdapter.toEvent(userId, record(method = "cheque"), "batch[0]") }
        assertTrue("upi" in e.message!! && e.message!!.startsWith("batch[0].paymentMethod"), e.message)
    }

    @Test
    fun `the fingerprint ignores merchant case and surrounding spaces`() {
        val a = SmsRecordAdapter.fingerprint(record(amount = 249.0).copy(merchant = "Swiggy "))
        val b = SmsRecordAdapter.fingerprint(record(amount = 249.0).copy(merchant = "swiggy"))
        assertEquals(a, b)
        assertNotEquals(a, SmsRecordAdapter.fingerprint(record(amount = 250.0)))
    }

    // ── Wire format ──

    @Test
    fun `SMS batch DTOs round-trip with and without defaults`() {
        assertRoundTrips(SmsBatchRequest.serializer(),
            """{"deviceId":"iphone","batch":[{"merchant":"SWIGGY","amount":249.0,"date":"2026-10-01","accountLast4":"1234","paymentMethod":"upi","direction":"credit","rawText":"x"}]}""",
            """{"deviceId":"iphone","batch":[{"merchant":"SWIGGY","amount":249.0,"date":"2026-10-01","accountLast4":"1234"}]}""")
        assertRoundTrips(SmsBatchResponse.serializer(), """{"syncSessionId":"s1","status":"completed","totalItems":1}""")
        assertRejectsMissingFields(SmsBatchRequest.serializer())
        assertRejectsMissingFields(SmsRecord.serializer())
        assertRejectsMissingFields(SmsBatchResponse.serializer())
    }

    @Test
    fun `sync session response round-trips with and without defaults`() {
        assertRoundTrips(SyncSessionResponse.serializer(),
            """{"id":"s1","connectionId":"c1","syncType":"incremental","status":"completed","totalItems":3,"processedItems":3,"failedItems":1,
               "duplicateItems":1,"netNewItems":1,"startedAt":"2026-10-01T00:00:00Z","completedAt":"2026-10-01T00:00:01Z"}""",
            """{"id":"s1","syncType":"historical","status":"queued"}""")
        assertRejectsMissingFields(SyncSessionResponse.serializer())
    }

    // ── IngestionService ──

    private val sessions = InMemorySyncSessionRepository()
    private val service = IngestionService(IngestEventService(InMemoryIngestEventRepository()), sessions)

    @Test
    fun `a Gmail ingest with a history id is an incremental queued session`() = runBlocking<Unit> {
        val s = service.enqueueGmailIngest(userId, UUID.randomUUID(), historyId = "98765")
        assertEquals(SyncType.INCREMENTAL, s.syncType)
        assertEquals(SyncSessionStatus.QUEUED, s.status)
        assertEquals(s, service.getSyncSession(s.id))
    }

    @Test
    fun `a Gmail ingest without a history id is a historical sync`() = runBlocking<Unit> {
        assertEquals(SyncType.HISTORICAL, service.enqueueGmailIngest(userId, UUID.randomUUID()).syncType)
    }

    @Test
    fun `a user's sync sessions are listed newest first up to the limit`() = runBlocking<Unit> {
        repeat(3) { service.enqueueGmailIngest(userId, UUID.randomUUID()) }
        service.enqueueGmailIngest(UUID.randomUUID(), UUID.randomUUID())
        assertEquals(3, service.getUserSyncSessions(userId).size)
        assertEquals(2, service.getUserSyncSessions(userId, limit = 2).size)
    }

    @Test
    fun `raw SMS text in a record is refused by the service`() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> {
            service.ingestSmsBatch(userId, UUID.randomUUID(), listOf(record().copy(rawText = "Sent Rs.249")))
        }
    }

    @Test
    fun `updating a sync session stores it with a new updatedAt`() = runBlocking<Unit> {
        val s = service.enqueueGmailIngest(userId, UUID.randomUUID())
        val updated = sessions.update(s.copy(status = SyncSessionStatus.COMPLETED, updatedAt = Instant.EPOCH))
        assertEquals(SyncSessionStatus.COMPLETED, sessions.findById(s.id)!!.status)
        assertTrue(updated.updatedAt.isAfter(Instant.EPOCH))
    }

    // ── Routes ──

    private val owner = user()
    private fun syncTest(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        routeTest({ ingestionRoutes(service, ConnectionService(InMemoryConnectionRepository(), sessions, randomTokenEncryptor())) }, block)

    private fun batch(n: Int) = (0 until n).joinToString(",", """{"deviceId":"iphone","batch":[""", "]}") {
        """{"merchant":"SWIGGY","amount":${100 + it},"date":"2026-10-01","accountLast4":"1234"}"""
    }

    @Test
    fun `a batch over 100 records is a 400 INGEST_002`() = syncTest {
        val res = client.post("/api/v1/ingest/sms") { bearerAuth(tokenFor(owner)); contentType(ContentType.Application.Json); setBody(batch(101)) }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue("INGEST_002" in res.bodyAsText())
    }

    @Test
    fun `an empty batch is a 400 INGEST_004`() = syncTest {
        val res = client.post("/api/v1/ingest/sms") { bearerAuth(tokenFor(owner)); contentType(ContentType.Application.Json); setBody(batch(0)) }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue("INGEST_004" in res.bodyAsText())
    }

    @Test
    fun `another user's sync session is reported as not found`() = syncTest {
        val theirs = service.enqueueGmailIngest(UUID.randomUUID(), UUID.randomUUID())
        val res = client.get("/api/v1/sync/${theirs.id}") { bearerAuth(tokenFor(owner)) }
        assertEquals(HttpStatusCode.NotFound, res.status)
        assertTrue("SYNC_003" in res.bodyAsText())
    }

    @Test
    fun `a queued session has no start or completion time`() = syncTest {
        val mine = service.enqueueGmailIngest(owner.id, UUID.randomUUID())
        val data = Json.parseToJsonElement(client.get("/api/v1/sync/${mine.id}") { bearerAuth(tokenFor(owner)) }.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals("queued", data["status"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, data["startedAt"]); assertEquals(JsonNull, data["completedAt"])
    }

    @Test
    fun `a sync id that is not a UUID is a 400`() = syncTest {
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/sync/not-a-uuid") { bearerAuth(tokenFor(owner)) }.status)
    }

    @Test
    fun `a completed batch session reports its timings`() = syncTest {
        val res = client.post("/api/v1/ingest/sms") { bearerAuth(tokenFor(owner)); contentType(ContentType.Application.Json); setBody(batch(2)) }
        val id = Json.parseToJsonElement(res.bodyAsText()).jsonObject["data"]!!.jsonObject["syncSessionId"]!!.jsonPrimitive.content
        val data = Json.parseToJsonElement(client.get("/api/v1/sync/$id") { bearerAuth(tokenFor(owner)) }.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals(2, data["netNewItems"]!!.jsonPrimitive.content.toInt())
        assertTrue(data["startedAt"] !is JsonNull && data["completedAt"] !is JsonNull)
    }
}
