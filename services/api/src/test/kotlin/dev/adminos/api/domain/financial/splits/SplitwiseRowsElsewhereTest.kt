package dev.adminos.api.domain.financial.splits

import dev.adminos.api.domain.financial.Bill
import dev.adminos.api.domain.financial.BillPaymentMatcher
import dev.adminos.api.domain.financial.BillType
import dev.adminos.api.domain.financial.InMemoryCategoryRuleRepository
import dev.adminos.api.domain.financial.InMemorySubscriptionRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.SubscriptionDetectorService
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.ai.AiCategorizationService
import dev.adminos.api.domain.financial.ai.AiCategoryResult
import dev.adminos.api.domain.financial.ai.InMemoryAiCategoryCacheRepository
import dev.adminos.api.domain.financial.links.MatchCandidate
import dev.adminos.api.domain.financial.links.MatchDecision
import dev.adminos.api.domain.financial.links.RefundDecision
import dev.adminos.api.domain.financial.links.RefundReversalDetector
import dev.adminos.api.domain.financial.links.TransactionMatcher
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.UserCalendar
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A friend's share recorded from Splitwise (source `splitwise`) has no bank record behind it (AOS-911). The rest of the
 * accuracy engine must never treat it as one: no duplicate merge, no refund link, no bill payment, no subscription,
 * and never a question to the AI fallback (friends' names can be in what people typed).
 */
class SplitwiseRowsElsewhereTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val user = UUID.randomUUID()
    private val at = LocalDate.of(2026, 10, 4).atTime(12, 0).atZone(ist).toInstant()

    private fun share(amount: String = "1000.00", merchant: String = "Splitwise", at: Instant = this.at) = Transaction(
        userId = user, sourceType = SplitMatcher.SPLITWISE_SOURCE, type = TransactionType.DEBIT, amount = BigDecimal(amount),
        merchantName = merchant, categorySource = "rules", transactedAt = at
    )

    private fun bankDebit(amount: String = "1000.00") = Transaction(
        userId = user, sourceType = "sms", type = TransactionType.DEBIT, amount = BigDecimal(amount), merchantName = "Splitwise",
        transactedAt = at.plusSeconds(60)
    )

    @Test
    fun `a bank debit of the same amount is never merged into a Splitwise share, either way`() {
        val matcher = TransactionMatcher()
        assertEquals(MatchDecision.None, matcher.decide(bankDebit(), listOf(MatchCandidate(share()))))
        assertEquals(MatchDecision.None, matcher.decide(share(), listOf(MatchCandidate(bankDebit()))))
    }

    @Test
    fun `a credit is never linked as a refund of a Splitwise share`() {
        val credit = Transaction(userId = user, sourceType = "sms", type = TransactionType.CREDIT, amount = BigDecimal("1000.00"),
            merchantName = "Splitwise", merchantRaw = "REFUND Splitwise", transactedAt = at.plus(1, ChronoUnit.DAYS))
        assertEquals(RefundDecision.None, RefundReversalDetector().decide(credit, listOf(share())))
    }

    @Test
    fun `a Splitwise share never pays a bill`() {
        val bill = Bill(userId = user, billType = BillType.CREDIT_CARD, billerName = "HDFC Card", amount = BigDecimal("1000.00"), dueDate = LocalDate.of(2026, 10, 10))
        assertFalse(BillPaymentMatcher.canPayBills(share()))
        assertNull(BillPaymentMatcher.billFor(share(), listOf(bill)))
    }

    @Test
    fun `monthly Splitwise shares are not a subscription`() = runBlocking<Unit> {
        val txns = InMemoryTransactionRepository()
        val today = LocalDate.now(ist)
        (1..4).forEach { m -> txns.save(share(at = today.minusMonths(m.toLong()).atTime(12, 0).atZone(ist).toInstant())) }
        val found = SubscriptionDetectorService(txns, InMemorySubscriptionRepository(), UserCalendar(InMemoryUserRepository()),
            dev.adminos.api.domain.financial.InMemoryPriceRiseRepository(), dev.adminos.api.domain.financial.grouping.InMemoryMerchantAliasRepository()).detectSubscriptions(user)
        assertTrue(found.isEmpty())
    }

    @Test
    fun `the AI fallback never asks about a Splitwise row`() {
        val ai = AiCategorizationService({ _, _ -> AiCategoryResult.Unavailable("never called") }, InMemoryAiCategoryCacheRepository(),
            InMemoryCategoryRuleRepository(), InMemoryTransactionRepository(), dev.adminos.api.domain.ai.ALLOW_ALL_CONSENT, "test-model")
        assertFalse(ai.isCandidate(share(merchant = "Asha birthday dinner")))
        assertTrue(ai.isCandidate(bankDebit().copy(merchantName = "Asha Bakery", category = TransactionCategory.OTHER)), "a bank row with the same shape is")
    }

    @Test
    fun `a share is between zero and the amount, and totals count it`() {
        val t = bankDebit("4000.00")
        assertEquals(BigDecimal("4000.00"), t.countedAmount)
        assertEquals(BigDecimal("1000.00"), t.copy(ownShare = BigDecimal("1000.00")).countedAmount)
        kotlin.test.assertFailsWith<IllegalArgumentException> { t.copy(ownShare = BigDecimal("4000.01")) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { t.copy(ownShare = BigDecimal("-1")) }
        assertEquals(TransactionNature.SETTLEMENT, TransactionNature.valueOf("SETTLEMENT"))
    }

    @Test
    fun `a general update never clears a share, and only setOwnShare changes it`() = runBlocking<Unit> {
        val txns = InMemoryTransactionRepository()
        val t = txns.save(bankDebit("4000.00"))
        val stale = txns.findById(t.id)!!
        assertTrue(txns.setOwnShare(t.id, BigDecimal("1000.00")))
        txns.update(stale.copy(category = TransactionCategory.FOOD))
        assertEquals(BigDecimal("1000.00"), txns.findById(t.id)?.ownShare)
        assertEquals(TransactionCategory.FOOD, txns.findById(t.id)?.category)
        assertTrue(txns.setOwnShare(t.id, null))
        assertNull(txns.findById(t.id)?.ownShare)
        assertFalse(txns.setOwnShare(UUID.randomUUID(), BigDecimal.ONE))
    }
}
