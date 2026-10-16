package dev.adminos.api.domain.ingestion.cash

import dev.adminos.api.InfraIngestTestKit.assertRejectsMissingFields
import dev.adminos.api.InfraIngestTestKit.assertRoundTrips
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventService
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Cash quick-add note handling and the wire format (AOS-312). */
class IngestionCashCoverageTest {

    private val now = Instant.parse("2026-10-16T06:30:00Z")
    private val events = InMemoryIngestEventRepository()
    private val intake = CashIntake(IngestEventService(events, Clock.fixed(now, ZoneOffset.UTC)), Clock.fixed(now, ZoneOffset.UTC))
    private val user = UUID.randomUUID()
    private fun entry(note: String?) = CashEntry(clientRef = "cash-${UUID.randomUUID()}", amount = BigDecimal("40"), category = TransactionCategory.FOOD, note = note)

    @Test
    fun `no note means no merchant and the entry happens now`() = runBlocking<Unit> {
        intake.add(user, entry(note = null))
        val e = events.findByUser(user).single()
        assertNull(e.merchantRaw)
        assertEquals(now, e.occurredAt)
    }

    @Test
    fun `a blank note means no merchant`() = runBlocking<Unit> {
        intake.add(user, entry(note = "   "))
        assertNull(events.findByUser(user).single().merchantRaw)
    }

    @Test
    fun `a note is trimmed and becomes the merchant`() = runBlocking<Unit> {
        intake.add(user, entry(note = "  Chai at Ramu's  "))
        assertEquals("Chai at Ramu's", events.findByUser(user).single().merchantRaw)
    }

    @Test
    fun `a note over 255 characters is rejected`() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { intake.add(user, entry(note = "n".repeat(256))) }
    }

    @Test
    fun `cash DTOs round-trip with and without defaults`() {
        assertRoundTrips(CashEntryRequest.serializer(),
            """{"clientRef":"cash-0001-abcd","amount":"40","category":"food","note":"Chai","occurredAt":"2026-10-16T09:00:00+05:30"}""",
            """{"clientRef":"cash-0001-abcd","amount":"40","category":"food"}""")
        assertRoundTrips(CashEntryResponse.serializer(), """{"saved":true,"duplicate":false}""")
        assertRejectsMissingFields(CashEntryRequest.serializer())
        assertRejectsMissingFields(CashEntryResponse.serializer())
    }
}
