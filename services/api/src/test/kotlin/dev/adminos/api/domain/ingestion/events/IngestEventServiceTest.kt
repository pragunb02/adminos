package dev.adminos.api.domain.ingestion.events

import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IngestEventServiceTest {

    private val now = Instant.parse("2026-10-16T06:00:00Z")
    private val repo = InMemoryIngestEventRepository()
    private val service = IngestEventService(repo, Clock.fixed(now, ZoneOffset.UTC))
    private val userId = UUID.randomUUID()

    private fun event(ref: String = "ref-${UUID.randomUUID()}", source: IngestSource = IngestSource.SMS, at: Instant = now.minusSeconds(60)) =
        IngestEvent(userId = userId, source = source, sourceRef = ref, occurredAt = at, direction = IngestDirection.DEBIT, amount = BigDecimal("249.50"))

    @Test
    fun `new events are accepted`() = runBlocking<Unit> {
        assertEquals(IngestResult(accepted = 2, duplicates = 0), service.ingest(listOf(event(), event())))
    }

    @Test
    fun `resubmitting the same sourceRef is a duplicate, not a second event`() = runBlocking<Unit> {
        service.ingest(listOf(event("sms-1")))
        assertEquals(IngestResult(accepted = 0, duplicates = 1), service.ingest(listOf(event("sms-1"))))
        assertEquals(1, repo.findByUser(userId).size)
    }

    @Test
    fun `duplicates inside one batch are counted`() = runBlocking<Unit> {
        assertEquals(IngestResult(accepted = 1, duplicates = 1), service.ingest(listOf(event("x"), event("x"))))
    }

    @Test
    fun `same sourceRef from a different source is a different event`() = runBlocking<Unit> {
        assertEquals(2, service.ingest(listOf(event("id-1", IngestSource.SMS), event("id-1", IngestSource.GMAIL))).accepted)
    }

    @Test
    fun `empty and oversized batches are rejected`() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { service.ingest(emptyList()) }
        assertFailsWith<IllegalArgumentException> { service.ingest((1..101).map { event() }) }
    }

    @Test
    fun `events in the future beyond clock skew are rejected`() = runBlocking<Unit> {
        service.ingest(listOf(event(at = now.plusSeconds(5 * 60)))) // within 10 min skew: ok
        assertFailsWith<IllegalArgumentException> { service.ingest(listOf(event(at = now.plusSeconds(60 * 60)))) }
    }

    @Test
    fun `invalid money and identifiers are rejected by the model`() {
        assertFailsWith<IllegalArgumentException> { event().copy(amount = BigDecimal("-1.00")) }
        assertFailsWith<IllegalArgumentException> { event().copy(amount = BigDecimal("1.005")) }
        assertFailsWith<IllegalArgumentException> { event().copy(currency = "rupees") }
        assertFailsWith<IllegalArgumentException> { event().copy(accountHint = "12345") }
        assertFailsWith<IllegalArgumentException> { event(ref = " ") }
    }
}
