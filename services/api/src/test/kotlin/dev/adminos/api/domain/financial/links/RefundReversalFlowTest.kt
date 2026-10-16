package dev.adminos.api.domain.financial.links

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
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

/** AOS-209 through the real pipeline, and its effect on spending totals. */
class RefundReversalFlowTest {
    private val ist = ZoneId.of("Asia/Kolkata")
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
    private val suggestions = SuggestionService(links, txns)

    private fun at(day: Int, h: Int) = LocalDate.of(2026, 10, day).atTime(LocalTime.of(h, 0)).atZone(ist).toInstant()
    private fun ev(ref: String, dir: IngestDirection, day: Int, h: Int, amount: String, raw: String, source: IngestSource = IngestSource.SMS) =
        IngestEvent(userId = user, source = source, sourceRef = ref, occurredAt = at(day, h), direction = dir,
            amount = BigDecimal(amount), merchantRaw = raw, accountHint = "1234")
    private suspend fun ingest(vararg e: IngestEvent) { events.saveNew(e.toList()); while (processor.processBatch() > 0) {} }
    private suspend fun spend(cat: TransactionCategory) = txns.spendingByCategory(user, at(1, 0), at(28, 0))[cat] ?: BigDecimal.ZERO

    @Test
    fun `a failed payment and its reversal net to zero`() = runBlocking<Unit> {
        ingest(ev("d", IngestDirection.DEBIT, 1, 9, "249.00", "UPI-SWIGGY"))
        ingest(ev("c", IngestDirection.CREDIT, 1, 11, "249.00", "REV-UPI-SWIGGY"))
        assertEquals(setOf(TransactionNature.REVERSAL), txns.findByUserId(user, limit = 10).map { it.nature }.toSet())
        assertEquals(BigDecimal.ZERO, spend(TransactionCategory.FOOD))
    }

    @Test
    fun `a partial refund reduces that category's spending instead of counting as income`() = runBlocking<Unit> {
        ingest(ev("d", IngestDirection.DEBIT, 2, 10, "999.00", "AMAZON PAY"))
        ingest(ev("c", IngestDirection.CREDIT, 9, 10, "300.00", "AMAZON REFUND"))
        val refund = txns.findByUserId(user, limit = 10).single { it.amount.compareTo(BigDecimal("300.00")) == 0 }
        assertEquals(TransactionNature.REFUND, refund.nature); assertEquals(TransactionCategory.SHOPPING, refund.category)
        assertEquals(0, BigDecimal("699.00").compareTo(spend(TransactionCategory.SHOPPING)))
    }

    @Test
    fun `statement rows in any order still link the refund`() = runBlocking<Unit> {
        ingest(ev("c", IngestDirection.CREDIT, 12, 12, "500.00", "MYNTRA REFUND", IngestSource.STATEMENT),
               ev("d", IngestDirection.DEBIT, 3, 12, "500.00", "MYNTRA ORDER", IngestSource.STATEMENT))
        assertTrue(txns.findByUserId(user, limit = 10).any { it.nature == TransactionNature.REFUND })
    }

    @Test
    fun `a weak reversal is suggested and applied on confirm`() = runBlocking<Unit> {
        ingest(ev("d", IngestDirection.DEBIT, 4, 9, "150.00", "UPI-CHAI POINT"))
        ingest(ev("c", IngestDirection.CREDIT, 4, 18, "150.00", "UPI CR RAVI"))
        val s = suggestions.suggestions(user, LinkRelation.REVERSAL).single()
        assertTrue(suggestions.confirm(user, s.link.id))
        assertEquals(setOf(TransactionNature.REVERSAL), txns.findByUserId(user, limit = 10).map { it.nature }.toSet())
    }

    @Test
    fun `salary is still income`() = runBlocking<Unit> {
        ingest(ev("d", IngestDirection.DEBIT, 5, 9, "999.00", "AMAZON PAY"))
        ingest(ev("s", IngestDirection.CREDIT, 5, 10, "100000.00", "NEFT CR-ACME SALARY"))
        assertEquals(TransactionNature.INCOME, txns.findByUserId(user, limit = 10).single { it.type == dev.adminos.api.domain.financial.TransactionType.CREDIT }.nature)
    }
}
