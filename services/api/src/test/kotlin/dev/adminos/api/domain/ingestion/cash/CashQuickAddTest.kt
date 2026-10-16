package dev.adminos.api.domain.ingestion.cash

import dev.adminos.api.domain.financial.*
import dev.adminos.api.domain.financial.links.*
import dev.adminos.api.domain.ingestion.events.*
import dev.adminos.api.domain.ingestion.pipeline.*
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-204 end to end through the real pipeline: cash entries, idempotency, no dedup with bank rows, ATM = transfer (D-42). */
class CashQuickAddTest {
    private val now = Instant.parse("2026-10-16T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val user = UUID.randomUUID()
    private val events = InMemoryIngestEventRepository()
    private val txns = InMemoryTransactionRepository()
    private val links = InMemoryTransactionLinkRepository()
    private val candidates = RepositoryMatchCandidates(txns, links)
    private val processor = IngestProcessor(events, IngestPipeline(listOf(
        ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()), CrossSourceDedupStep(candidates, TransactionMatcher()),
        CategorizeStep(CategorizationService()), TransferStep(candidates, TransferDetector()),
        RefundReversalStep(txns, links, RefundReversalDetector())
    )), RepositoryOutcomeWriter(txns, events, links))
    private val intake = CashIntake(IngestEventService(events, clock), clock)

    private suspend fun drain() { while (processor.processBatch() > 0) {} }
    private fun ref() = UUID.randomUUID().toString()
    private suspend fun sms(amount: String, raw: String, at: Instant) {
        events.saveNew(listOf(IngestEvent(userId = user, source = IngestSource.SMS, sourceRef = ref(), occurredAt = at,
            direction = IngestDirection.DEBIT, amount = BigDecimal(amount), merchantRaw = raw, accountHint = "1234")))
    }

    @Test
    fun `amount and category save a cash spend with the chosen category`() = runBlocking<Unit> {
        assertTrue(intake.add(user, CashEntry(ref(), BigDecimal("250"), TransactionCategory.FOOD, note = "chai")))
        drain()
        val t = txns.findByUserId(user, limit = 10).single()
        assertEquals(TransactionNature.SPEND, t.nature); assertEquals(PaymentMethod.CASH, t.paymentMethod)
        assertEquals(TransactionCategory.FOOD, t.category); assertEquals("user", t.categorySource)
        assertEquals("Chai", t.merchantName); assertEquals("manual", t.sourceType); assertEquals(now, t.transactedAt)
    }

    @Test
    fun `the categorizer never overrides the chosen category`() = runBlocking<Unit> {
        intake.add(user, CashEntry(ref(), BigDecimal("80"), TransactionCategory.HEALTH, note = "Uber")) // "Uber" would be transport
        drain()
        assertEquals(TransactionCategory.HEALTH, txns.findByUserId(user, limit = 1).single().category)
    }

    @Test
    fun `a double tap or retry is saved once`() = runBlocking<Unit> {
        val r = ref()
        assertTrue(intake.add(user, CashEntry(r, BigDecimal("40"), TransactionCategory.FOOD)))
        assertFalse(intake.add(user, CashEntry(r, BigDecimal("40"), TransactionCategory.FOOD)))
        drain()
        assertEquals(1, txns.findByUserId(user, limit = 10).size)
    }

    @Test
    fun `bad entries are rejected with the field named`() = runBlocking<Unit> {
        fun bad(e: CashEntry, field: String) = runBlocking {
            val ex = assertFailsWith<IllegalArgumentException> { intake.add(user, e) }
            assertTrue(ex.message!!.contains(field), ex.message)
        }
        bad(CashEntry(ref(), BigDecimal.ZERO, TransactionCategory.FOOD), "amount")
        bad(CashEntry(ref(), BigDecimal("-5"), TransactionCategory.FOOD), "amount")
        bad(CashEntry(ref(), BigDecimal("10000000.01"), TransactionCategory.FOOD), "amount")
        bad(CashEntry(ref(), BigDecimal("1.234"), TransactionCategory.FOOD), "amount")
        bad(CashEntry("short", BigDecimal("1"), TransactionCategory.FOOD), "clientRef")
        bad(CashEntry(ref(), BigDecimal("1"), TransactionCategory.FOOD, note = "x".repeat(256)), "note")
        bad(CashEntry(ref(), BigDecimal("1"), TransactionCategory.FOOD, occurredAt = now.plus(1, ChronoUnit.HOURS)), "future")
    }

    @Test
    fun `cash is never matched with a bank transaction of the same amount and time`() = runBlocking<Unit> {
        sms("500.00", "UPI-SHARMA STORES", now.minus(5, ChronoUnit.MINUTES)); drain()
        intake.add(user, CashEntry(ref(), BigDecimal("500"), TransactionCategory.SHOPPING, note = "Sharma Stores")); drain()
        assertEquals(2, txns.findByUserId(user, limit = 10).size)
        assertTrue(links.findByUser(user, LinkKind.SUGGESTED).isEmpty())
    }

    @Test
    fun `an ATM withdrawal moves money to cash, so only the cash spent counts`() = runBlocking<Unit> {
        sms("2000.00", "ATM WDL HDFC BANK MG ROAD", now.minus(2, ChronoUnit.HOURS)); drain()
        intake.add(user, CashEntry(ref(), BigDecimal("250"), TransactionCategory.FOOD)); drain()
        val atm = txns.findByUserId(user, limit = 10).single { it.paymentMethod != PaymentMethod.CASH }
        assertEquals(TransactionNature.TRANSFER, atm.nature); assertEquals("cash", atm.subcategory)
        val spend = txns.spendingByCategory(user, now.minus(1, ChronoUnit.DAYS), now)
        assertEquals(setOf(TransactionCategory.FOOD), spend.keys)
        assertEquals(0, BigDecimal("250").compareTo(spend[TransactionCategory.FOOD]))
    }
}
