package dev.adminos.api.domain.ingestion.fallback.evaluation

import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository
import dev.adminos.api.domain.financial.mandates.MandateService
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.fallback.FallbackOffer
import dev.adminos.api.domain.ingestion.fallback.UnrecognisedMessage
import dev.adminos.api.domain.ingestion.fallback.UnrecognisedMessageSink
import dev.adminos.api.domain.ingestion.sms.SmsIntake
import dev.adminos.api.domain.ingestion.sms.SmsMessage
import dev.adminos.api.domain.ingestion.sms.SmsParseResult
import dev.adminos.api.domain.ingestion.sms.SmsParserRegistry
import dev.adminos.api.domain.ingestion.whatsapp.BankSenders
import dev.adminos.api.domain.ingestion.whatsapp.WhatsAppImportService
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which golden messages reach the AI fallback (AOS-705, D-130), through the shipped SMS and WhatsApp intake and
 * against **today's** regex rules: a message the rules read never reaches it, and a bank message they can't read is
 * offered to it. The fallback's own quality is measured separately (`MessageEvaluationTest`), so a parser that
 * learns more formats moves rows from one half to the other here without touching that eval.
 */
class MessageRoutingTest {
    private val parsers = SmsParserRegistry()
    private val rows = MessageGoldenSet.load()
    private val clock = Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC)

    /** What the rules alone make of [row], as its own channel's intake asks them. */
    private fun rules(row: GoldenMessage): SmsParseResult = when (row.channel) {
        "sms" -> parsers.parse(SmsMessage(row.sender, row.text, row.receivedAt))
        "whatsapp" -> when (val bank = BankSenders.match(row.sender)) {
            null -> SmsParseResult.Ignored("not a bank sender")
            else -> parsers.parseFromBank(row.text, (bank as? BankSenders.Bank)?.code)
        }
        else -> error("unknown channel ${row.channel}")
    }

    /** The messages the shipped intake offered to the fallback for [row]. */
    private fun offered(row: GoldenMessage): List<UnrecognisedMessage> = runBlocking {
        val seen = mutableListOf<UnrecognisedMessage>()
        val sink = UnrecognisedMessageSink { seen += it; FallbackOffer.Queued }
        val mandates = MandateService(InMemoryMandateRepository(), InMemoryTransactionRepository())
        val intake = IngestEventService(InMemoryIngestEventRepository(), clock)
        val user = UUID.randomUUID()
        when (row.channel) {
            "sms" -> SmsIntake(SmsParserRegistry(), intake, mandates, dev.adminos.api.domain.financial.paylater.testPayLaterBills(), dev.adminos.api.domain.financial.trips.TravelBookings(dev.adminos.api.domain.financial.trips.InMemoryTravelBookingRepository()), sink) { MessageEvaluation.ZONE }
                .accept(user, SmsMessage(row.sender, row.text, row.receivedAt))
            "whatsapp" -> WhatsAppImportService(SmsParserRegistry(), intake, mandates, UserCalendar(InMemoryUserRepository()), clock, sink)
                .import(user, MessageEvaluation.whatsAppExport(row))
            else -> error("unknown channel ${row.channel}")
        }
        seen
    }

    @Test
    fun `a message the rules read never reaches the AI`() {
        val read = rows.filter { rules(it) != SmsParseResult.Unrecognised }
        assertTrue(read.size >= 10, "the golden set has messages today's rules read or drop (${read.size})")
        val leaked = read.filter { offered(it).isNotEmpty() }.map { "${it.id} (${rules(it)::class.simpleName})" }
        assertEquals(emptyList(), leaked)
    }

    @Test
    fun `a bank message the rules can't read is offered to the AI, once and as written`() {
        val unread = rows.filter { rules(it) == SmsParseResult.Unrecognised }
        assertTrue(unread.size >= 10, "the golden set has messages today's rules can't read (${unread.size})")
        val wrong = unread.mapNotNull { row ->
            val o = offered(row)
            if (o.size == 1 && o.single().text == row.text && o.single().receivedAt == row.receivedAt) null else "${row.id}: offered ${o.size}"
        }
        assertEquals(emptyList(), wrong)
    }
}
