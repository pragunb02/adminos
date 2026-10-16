package dev.adminos.api.domain.financial.links

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.ingestion.events.*
import dev.adminos.api.domain.ingestion.pipeline.*
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** AOS-207 through the real pipeline: intake events from several sources → one transaction per real payment. */
class CrossSourceDedupFlowTest {

    private val ist = ZoneId.of("Asia/Kolkata")
    private val user = UUID.randomUUID()
    private val events = InMemoryIngestEventRepository()
    private val txns = InMemoryTransactionRepository()
    private val links = InMemoryTransactionLinkRepository()
    private val processor = IngestProcessor(
        events,
        IngestPipeline(listOf(ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()),
            CrossSourceDedupStep(RepositoryMatchCandidates(txns, links), TransactionMatcher()), CategorizeStep(CategorizationService()))),
        RepositoryOutcomeWriter(txns, events, links)
    )
    private val duplicates = SuggestionService(links, txns)

    private fun at(day: Int, h: Int, m: Int = 0) = LocalDate.of(2026, 10, day).atTime(LocalTime.of(h, m)).atZone(ist).toInstant()

    private fun sms(ref: String, day: Int, h: Int, amount: String = "249.00", merchant: String = "SWIGGY", rrn: String? = null) = IngestEvent(
        userId = user, source = IngestSource.SMS, sourceRef = ref, occurredAt = at(day, h), direction = IngestDirection.DEBIT,
        amount = BigDecimal(amount), merchantRaw = merchant, accountHint = "1234", paymentMethod = PaymentMethod.UPI, paymentRef = rrn
    )
    private fun row(ref: String, day: Int, amount: String = "249.00", narration: String = "UPI-SWIGGY-swiggy@icici", rrn: String? = null) = IngestEvent(
        userId = user, source = IngestSource.STATEMENT, sourceRef = ref, occurredAt = at(day, 12), direction = IngestDirection.DEBIT,
        amount = BigDecimal(amount), merchantRaw = narration, accountHint = "1234", paymentMethod = PaymentMethod.UPI,
        upiVpa = "swiggy@icici", paymentRef = rrn
    )

    private suspend fun ingest(vararg e: IngestEvent) { events.saveNew(e.toList()); while (processor.processBatch() > 0) {} }
    private suspend fun all() = txns.findByUserId(user, limit = 100)

    @Test
    fun `sms then statement is one transaction, enriched, with a merge link`() = runBlocking<Unit> {
        ingest(sms("s1", 1, 9))
        ingest(row("r1", 1, rrn = "123456789012"))
        val t = all().single()
        assertEquals("sms", t.sourceType, "the first source is kept")
        assertEquals("swiggy@icici", t.upiVpa); assertEquals("123456789012", t.paymentRef) // filled from the statement
        assertEquals(setOf("statement"), links.mergedSources(listOf(t.id))[t.id])
        assertTrue(events.findByUser(user).first { it.sourceRef == "r1" }.error!!.startsWith("merged into"))
    }

    @Test
    fun `statement then sms works the same way`() = runBlocking<Unit> {
        ingest(row("r1", 1))
        ingest(sms("s1", 1, 9))
        assertEquals("statement", all().single().sourceType)
    }

    @Test
    fun `same reference merges even on different days`() = runBlocking<Unit> {
        ingest(sms("s1", 1, 23, rrn = "555555555555"))
        ingest(row("r1", 3, narration = "UPI ORDER", rrn = "555555555555"))
        assertEquals(1, all().size)
    }

    @Test
    fun `two genuine teas and two statement rows - nothing auto-merged, suggestions created`() = runBlocking<Unit> {
        ingest(sms("t1", 3, 9, amount = "10.00", merchant = "TEA STALL"), sms("t2", 3, 17, amount = "10.00", merchant = "TEA STALL"))
        ingest(row("r1", 3, amount = "10.00", narration = "UPI-TEA STALL"), row("r2", 3, amount = "10.00", narration = "UPI-TEA STALL"))
        assertEquals(4, all().size, "ambiguous pairs are never merged automatically")
        assertTrue(duplicates.suggestions(user).isNotEmpty())
    }

    @Test
    fun `a different amount or account stays separate`() = runBlocking<Unit> {
        ingest(sms("s1", 1, 9))
        ingest(row("r1", 1, amount = "250.00"), row("r2", 1).copy(accountHint = "9999"))
        assertEquals(3, all().size)
    }

    @Test
    fun `merging never overwrites the user's edits`() = runBlocking<Unit> {
        ingest(sms("s1", 1, 9))
        val edited = all().single().copy(category = TransactionCategory.ENTERTAINMENT, merchantName = "Team lunch")
        txns.update(edited)
        ingest(row("r1", 1))
        val t = all().single()
        assertEquals(TransactionCategory.ENTERTAINMENT, t.category); assertEquals("Team lunch", t.merchantName)
    }

    @Test
    fun `confirming a suggestion removes the duplicate and records the merge, rejecting is remembered`() = runBlocking<Unit> {
        ingest(sms("s1", 1, 9, merchant = "SHOP A"))
        ingest(IngestEvent(userId = user, source = IngestSource.MANUAL, sourceRef = "m1", occurredAt = at(1, 20), direction = IngestDirection.DEBIT,
            amount = BigDecimal("249.00"), merchantRaw = "Shop A cash"))
        val s = duplicates.suggestions(user).single()
        assertEquals("manual", s.related.sourceType)

        assertEquals(false, duplicates.confirm(UUID.randomUUID(), s.link.id), "another user can't confirm it")
        assertTrue(duplicates.confirm(user, s.link.id))
        assertEquals(1, all().size)
        assertEquals(LinkKind.MERGED, links.findById(s.link.id)!!.kind)
        assertEquals(false, duplicates.confirm(user, s.link.id), "already decided")

        ingest(sms("s2", 2, 9, amount = "99.00", merchant = "SHOP B"))
        ingest(IngestEvent(userId = user, source = IngestSource.MANUAL, sourceRef = "m2", occurredAt = at(2, 19), direction = IngestDirection.DEBIT,
            amount = BigDecimal("99.00"), merchantRaw = "Shop B"))
        val s2 = duplicates.suggestions(user).single()
        assertTrue(duplicates.reject(user, s2.link.id))
        assertEquals(LinkKind.REJECTED, links.findById(s2.link.id)!!.kind)
        assertEquals(3, all().size)
        assertTrue(duplicates.suggestions(user).none { it.link.id == s2.link.id }, "a rejected pair is not listed again")
    }
}
