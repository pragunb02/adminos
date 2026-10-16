package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventService
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmsIntakeTest {
    private val repo = InMemoryIngestEventRepository()
    private val intake = SmsIntake(SmsParserRegistry(), IngestEventService(repo))
    private val userId = UUID.randomUUID()
    private val text = "Sent Rs.249.00 From HDFC Bank A/C *1234 To SWIGGY On 16/10/26 Ref 123456789012"

    @Test
    fun `financial sms becomes one intake event without the raw text`() = runBlocking<Unit> {
        assertEquals(SmsIntakeResult.Accepted, intake.accept(userId, SmsMessage("AX-HDFCBK-S", text, Instant.parse("2026-10-01T03:42:00Z"))))
        val e = repo.findByUser(userId).single()
        assertEquals("SWIGGY", e.merchantRaw)
        assertTrue(text !in e.toString() && "Ref 123456789012" !in e.toString(), "raw text must not be stored (D-33)")
    }

    @Test
    fun `the same sms from another operator prefix or resent is a duplicate`() = runBlocking<Unit> {
        intake.accept(userId, SmsMessage("AX-HDFCBK-S", text, Instant.parse("2026-10-01T03:42:00Z")))
        assertEquals(SmsIntakeResult.Duplicate, intake.accept(userId, SmsMessage("VM-HDFCBK-S", text, Instant.parse("2026-10-01T03:43:00Z"))))
        assertEquals(1, repo.findByUser(userId).size)
    }

    @Test
    fun `non-financial sms is ignored and nothing is stored`() = runBlocking<Unit> {
        assertIs<SmsIntakeResult.Ignored>(intake.accept(userId, SmsMessage("AX-HDFCBK-T", "OTP 123456 for Rs.249.00", Instant.EPOCH)))
        assertEquals(0, repo.findByUser(userId).size)
    }
}
