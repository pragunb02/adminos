package dev.adminos.api.domain.ingestion.whatsapp

import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository
import dev.adminos.api.domain.financial.mandates.MandateService
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.IngestSource
import dev.adminos.api.domain.ingestion.sms.SmsParserRegistry
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WhatsAppImportServiceTest {
    private val now = Instant.parse("2026-10-17T06:00:00Z")
    private val events = InMemoryIngestEventRepository()
    private val mandateRepo = InMemoryMandateRepository()
    private val service = WhatsAppImportService(
        SmsParserRegistry(), IngestEventService(events),
        MandateService(mandateRepo, InMemoryTransactionRepository()),
        UserCalendar(InMemoryUserRepository()), Clock.fixed(now, ZoneOffset.UTC)
    )
    private val userId = UUID.randomUUID()

    private val chat = """
        16/10/26, 09:12 - Messages and calls are end-to-end encrypted.
        16/10/26, 09:12 - HDFC Bank: Sent Rs.249.00 From HDFC Bank A/C *1234 To SWIGGY On 16/10/26 Ref 123456789012
        16/10/26, 09:20 - HDFC Bank: OTP is 123456 for txn of Rs.249.00 at SWIGGY. Do not share.
        16/10/26, 10:00 - HDFC Bank: Your UPI AutoPay mandate for NETFLIX of Rs 649.00 Monthly has been successfully created. UMN: 7a8b9c0d1e@hdfcbank - HDFC Bank
        16/10/26, 11:00 - Yes Bank: Rs.1,000.00 debited from A/c XX9876 for UPI-ZOMATO-zomato@ybl-REF1
        16/10/26, 12:00 - Ravi: Sent Rs.500.00 to you, pay me back
        17/10/26, 23:00 - HDFC Bank: Sent Rs.99.00 From HDFC Bank A/C *1234 To LATE
    """.trimIndent().toByteArray()

    @Test
    fun `bank messages become WhatsApp events, mandates are applied, and other senders are never parsed`() = runBlocking<Unit> {
        val r = service.import(userId, chat)
        assertEquals(6, r.messages)
        assertEquals(5, r.fromBanks)
        assertEquals(2, r.accepted)
        assertEquals(1, r.mandates)
        assertEquals(mapOf("one-time password" to 1, WhatsAppImportService.DATED_IN_FUTURE to 1), r.ignored)

        val saved = events.findByUser(userId).sortedBy { it.occurredAt }
        assertTrue(saved.all { it.source == IngestSource.WHATSAPP })
        assertEquals(listOf(BigDecimal("249.00"), BigDecimal("1000.00")), saved.map { it.amount.setScale(2) })
        assertEquals("hdfc", saved[0].metadata?.get("bank"))
        assertEquals(Instant.parse("2026-10-16T03:42:00Z"), saved[0].occurredAt, "times are read in the user's zone (India by default)")
        assertTrue(saved.none { "Ravi" in it.toString() || "pay me back" in it.toString() })
        assertEquals(1, mandateRepo.findByUserId(userId).size)
    }

    @Test
    fun `importing the same chat again, or a longer export of it, adds only new messages`() = runBlocking<Unit> {
        service.import(userId, chat)
        val again = service.import(userId, chat)
        assertEquals(0, again.accepted)
        assertEquals(2, again.duplicates)
        assertEquals(1, mandateRepo.findByUserId(userId).size)

        val longer = service.import(userId, chat + "\n16/10/26, 13:00 - HDFC Bank: Sent Rs.10.00 From HDFC Bank A/C *1234 To TEA".toByteArray())
        assertEquals(1, longer.accepted)
        assertEquals(2, longer.duplicates)
    }

    @Test
    fun `a chat with no bank messages imports nothing and says so in the counts`() = runBlocking<Unit> {
        val r = service.import(userId, "16/10/26, 09:12 - Ravi: hi\n16/10/26, 09:13 - Ravi: Sent Rs.500".toByteArray())
        assertEquals(2, r.messages)
        assertEquals(0, r.fromBanks)
        assertEquals(0, r.accepted)
        assertEquals(0, events.findByUser(userId).size)
    }

    @Test
    fun `more than one ingest batch of messages is imported in chunks`() = runBlocking<Unit> {
        val many = (1..150).joinToString("\n") { i ->
            "16/10/26, 09:${"%02d".format(i % 60)} - HDFC Bank: Sent Rs.$i.00 From HDFC Bank A/C *1234 To SHOP$i"
        }
        assertEquals(150, service.import(userId, many.toByteArray()).accepted)
    }
}
