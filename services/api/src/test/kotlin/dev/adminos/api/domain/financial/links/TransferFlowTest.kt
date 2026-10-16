package dev.adminos.api.domain.financial.links

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
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
import kotlin.test.assertTrue

/** AOS-208 through the real pipeline. */
class TransferFlowTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val user = UUID.randomUUID()
    private val events = InMemoryIngestEventRepository()
    private val txns = InMemoryTransactionRepository()
    private val links = InMemoryTransactionLinkRepository()
    private val candidates = RepositoryMatchCandidates(txns, links)
    private val processor = IngestProcessor(events, IngestPipeline(listOf(
        ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()), CrossSourceDedupStep(candidates, TransactionMatcher()),
        CategorizeStep(CategorizationService()), TransferStep(candidates, TransferDetector())
    )), RepositoryOutcomeWriter(txns, events, links))
    private val suggestions = SuggestionService(links, txns)

    private fun at(day: Int, h: Int, m: Int = 0) = LocalDate.of(2026, 10, day).atTime(LocalTime.of(h, m)).atZone(ist).toInstant()
    private fun ev(ref: String, dir: IngestDirection, last4: String, day: Int, h: Int, m: Int = 0, text: String, amount: String = "5000.00") =
        IngestEvent(userId = user, source = IngestSource.SMS, sourceRef = ref, occurredAt = at(day, h, m), direction = dir,
            amount = BigDecimal(amount), merchantRaw = text, accountHint = last4, paymentMethod = PaymentMethod.NETBANKING)
    private suspend fun ingest(vararg e: IngestEvent) { events.saveNew(e.toList()); while (processor.processBatch() > 0) {} }

    @Test
    fun `money moved between own accounts is neither spending nor income`() = runBlocking<Unit> {
        ingest(ev("out", IngestDirection.DEBIT, "1234", 1, 9, 0, "IMPS to A/c XX5678"))
        ingest(ev("in", IngestDirection.CREDIT, "5678", 1, 9, 1, "IMPS from A/c XX1234"))
        val all = txns.findByUserId(user, limit = 10)
        assertEquals(setOf(TransactionNature.TRANSFER), all.map { it.nature }.toSet())
        assertTrue(all.all { it.category == TransactionCategory.TRANSFER })
        assertTrue(txns.spendingByCategory(user, at(1, 0), at(2, 0)).isEmpty(), "transfers are not spending")
        assertEquals(LinkRelation.TRANSFER, links.findByTransaction(all.first().id).single().relation)
    }

    @Test
    fun `a card bill payment is a transfer, so card spending isn't counted twice`() = runBlocking<Unit> {
        ingest(ev("bill", IngestDirection.DEBIT, "1234", 5, 10, text = "BILLDESK*HDFC CREDIT CARD", amount = "12500.00"))
        val bill = txns.findByUserId(user, limit = 10).single()
        assertEquals(TransactionNature.TRANSFER, bill.nature); assertEquals("credit_card_bill", bill.subcategory)
        assertTrue(txns.spendingByCategory(user, at(5, 0), at(6, 0)).isEmpty())
    }

    @Test
    fun `a weak pair is suggested and becomes a transfer when confirmed`() = runBlocking<Unit> {
        ingest(ev("out", IngestDirection.DEBIT, "1234", 2, 9, 0, "PAYMENT"))
        ingest(ev("in", IngestDirection.CREDIT, "5678", 2, 9, 4, "RECEIVED"))
        assertEquals(setOf(TransactionNature.SPEND, TransactionNature.INCOME), txns.findByUserId(user, limit = 10).map { it.nature }.toSet())
        val s = suggestions.suggestions(user, LinkRelation.TRANSFER).single()
        assertTrue(suggestions.confirm(user, s.link.id))
        assertEquals(setOf(TransactionNature.TRANSFER), txns.findByUserId(user, limit = 10).map { it.nature }.toSet())
    }

    @Test
    fun `paying a friend is still spending`() = runBlocking<Unit> {
        ingest(ev("p", IngestDirection.DEBIT, "1234", 3, 9, text = "UPI-RAVI KUMAR-ravi@okhdfc", amount = "500.00"))
        assertEquals(TransactionNature.SPEND, txns.findByUserId(user, limit = 10).single().nature)
    }
}
