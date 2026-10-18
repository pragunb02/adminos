package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventService
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmsIntakeTest {
    private val repo = InMemoryIngestEventRepository()
    private val balanceRepo = dev.adminos.api.domain.balances.InMemoryBalanceRepository()
    private val balances = dev.adminos.api.domain.balances.BalanceService(balanceRepo, java.time.Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), java.time.ZoneOffset.UTC))
    private val intake = SmsIntake(SmsParserRegistry(), IngestEventService(repo), dev.adminos.api.domain.financial.mandates.MandateService(dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository(), dev.adminos.api.domain.financial.InMemoryTransactionRepository()), balances)
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

    @Test
    fun `a trusted bank's Avl Bal is noted for the account beside the transaction`() = runBlocking<Unit> {
        val sms = "Rs.500.00 debited from A/c XX4321 on 16-10-26 to VPA shop@okaxis. Avl Bal Rs 12,345.60"
        val out = intake.receive(userId, SmsMessage("AX-HDFCBK-S", sms, Instant.parse("2026-10-16T05:00:00Z")))
        assertEquals(SmsIntakeResult.Accepted, out.result)
        assertTrue(out.balanceNoted)
        val r = balanceRepo.forUser(userId).single()
        assertEquals(java.math.BigDecimal("12345.60"), r.amount)
        assertEquals(dev.adminos.api.domain.balances.BalanceAccount("hdfc", "4321"), r.account)
        assertEquals(Instant.parse("2026-10-16T05:00:00Z"), r.observedAt)
        assertTrue("12345" !in r.toString() && "4321" !in out.toString())
        assertFalse(intake.receive(userId, SmsMessage("VM-HDFCBK-S", sms, Instant.parse("2026-10-16T05:01:00Z"))).balanceNoted, "a resent SMS is the same reading")
    }

    @Test
    fun `a pure balance alert gives no transaction but notes the balance as on its date`() = runBlocking<Unit> {
        val sms = "Available balance in A/c XX4321 is Rs 9,000.00 CR as on 14-10-2026"
        val out = intake.receive(userId, SmsMessage("JD-SBIINB-S", sms, Instant.parse("2026-10-16T05:00:00Z")))
        assertEquals(SmsIntakeResult.Ignored(SmsParseResult.BALANCE_ALERT), out.result)
        assertTrue(out.balanceNoted)
        assertEquals(Instant.parse("2026-10-14T18:29:59Z"), balanceRepo.forUser(userId).single().observedAt, "the end of the day the bank names")
        assertEquals(0, repo.findByUser(userId).size)
    }

    @Test
    fun `balances from generic, promotional or personal senders are never noted`() = runBlocking<Unit> {
        val sms = "Rs.500.00 debited from A/c XX4321. Avl Bal Rs 99,99,999.00"
        for (sender in listOf("AX-NEWBNK-S", "AX-HDFCBK-P", "+919800000000")) {
            assertFalse(intake.receive(userId, SmsMessage(sender, sms, Instant.parse("2026-10-16T05:00:00Z"))).balanceNoted, sender)
        }
        assertEquals(emptyList(), balanceRepo.forUser(userId))
    }
}
