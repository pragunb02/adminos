package dev.adminos.api.domain.financial.splits

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SplitMatcherTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val user = UUID.randomUUID()
    private val matcher = SplitMatcher(ist)
    private val day = LocalDate.of(2026, 10, 3)

    private fun bill(amount: String = "4000.00", description: String = "Dinner at Toit", category: String? = "Dining out") = MatchQuery(
        userId = user, effect = SplitEffect.ADJUST_DEBIT, date = day, bankAmount = BigDecimal(amount), currency = "INR",
        description = description, category = category, counterparty = null
    )

    private fun settle(effect: SplitEffect = SplitEffect.SETTLE_IN, friend: String? = "Asha R.") = MatchQuery(
        userId = user, effect = effect, date = day, bankAmount = BigDecimal("1500.00"), currency = "INR",
        description = "Payment", category = "Payment", counterparty = friend
    )

    private fun txn(
        amount: String = "4000.00", offsetDays: Long = 0, type: TransactionType = TransactionType.DEBIT,
        merchant: String? = "Swiggy", raw: String? = null, vpa: String? = null, method: PaymentMethod? = PaymentMethod.CARD_CREDIT,
        category: TransactionCategory = TransactionCategory.OTHER, nature: TransactionNature = TransactionNature.defaultFor(type),
        source: String = "statement", userId: UUID = user, ownShare: BigDecimal? = null, currency: String = "INR"
    ) = Transaction(
        userId = userId, sourceType = source, type = type, amount = BigDecimal(amount), nature = nature, merchantName = merchant,
        merchantRaw = raw, upiVpa = vpa, paymentMethod = method, category = category, currency = currency,
        transactedAt = day.plusDays(offsetDays).atTime(20, 0).atZone(ist).toInstant(), ownShare = ownShare
    )

    @Test
    fun `the only same-day debit of the whole bill is applied`() {
        val t = txn()
        val m = assertIs<SplitMatch.Apply>(matcher.decide(bill(), listOf(t)))
        assertEquals(t.id, m.transaction.id)
        assertTrue("same day" in m.reason)
    }

    @Test
    fun `a debit a day later is only suggested, unless the merchant or category confirms it`() {
        assertIs<SplitMatch.Suggest>(matcher.decide(bill(), listOf(txn(offsetDays = 1))))
        assertIs<SplitMatch.Apply>(matcher.decide(bill(), listOf(txn(offsetDays = 1, merchant = "Toit Brewpub"))))
        assertIs<SplitMatch.Apply>(matcher.decide(bill(), listOf(txn(offsetDays = 1, category = TransactionCategory.FOOD))))
    }

    @Test
    fun `cards post late, so a bill can match from a day before to three days after`() {
        assertIs<SplitMatch.Suggest>(matcher.decide(bill(), listOf(txn(offsetDays = -1))))
        assertIs<SplitMatch.Suggest>(matcher.decide(bill(), listOf(txn(offsetDays = 3))))
        assertEquals(SplitMatch.None, matcher.decide(bill(), listOf(txn(offsetDays = -2))))
        assertEquals(SplitMatch.None, matcher.decide(bill(), listOf(txn(offsetDays = 4))))
    }

    @Test
    fun `two equally good debits are ambiguous and only suggested`() {
        val m = assertIs<SplitMatch.Suggest>(matcher.decide(bill(), listOf(txn(), txn())))
        assertTrue("another similar transaction" in m.reason)
    }

    @Test
    fun `hard rules exclude the wrong amount, direction, nature, user, currency, source and split ones`() {
        val wrong = listOf(
            txn(amount = "3999.00"), txn(type = TransactionType.CREDIT), txn(nature = TransactionNature.TRANSFER),
            txn(userId = UUID.randomUUID()), txn(currency = "USD"), txn(source = SplitMatcher.SPLITWISE_SOURCE),
            txn(ownShare = BigDecimal("1000.00"))
        )
        wrong.forEach { assertEquals(SplitMatch.None, matcher.decide(bill(), listOf(it)), it.toString()) }
        val rejected = txn()
        assertEquals(SplitMatch.None, matcher.decide(bill().copy(excluded = setOf(rejected.id)), listOf(rejected)))
    }

    @Test
    fun `a settle-up is applied only with the friend's name on the payment`() {
        val named = txn(amount = "1500.00", type = TransactionType.CREDIT, merchant = "Asha Rao", method = PaymentMethod.UPI)
        assertIs<SplitMatch.Apply>(matcher.decide(settle(), listOf(named)))
        val unnamed = txn(amount = "1500.00", type = TransactionType.CREDIT, merchant = "Neft Credit", method = PaymentMethod.UPI)
        val m = assertIs<SplitMatch.Suggest>(matcher.decide(settle(), listOf(unnamed)))
        assertTrue("friend's name" in m.reason)
        assertIs<SplitMatch.Suggest>(matcher.decide(settle(friend = null), listOf(named)), "no single counterparty: suggest")
    }

    @Test
    fun `the friend's name can be in the narration or the UPI handle`() {
        val byRaw = txn(amount = "1500.00", type = TransactionType.DEBIT, merchant = null, raw = "UPI/DR/123/ASHA RAO/okicici", method = PaymentMethod.UPI)
        assertIs<SplitMatch.Apply>(matcher.decide(settle(SplitEffect.SETTLE_OUT), listOf(byRaw)))
        val byVpa = txn(amount = "1500.00", type = TransactionType.DEBIT, merchant = "Upi Payment", vpa = "asharao99@okaxis", method = PaymentMethod.UPI)
        assertIs<SplitMatch.Apply>(matcher.decide(settle(SplitEffect.SETTLE_OUT), listOf(byVpa)))
    }

    @Test
    fun `a settle-up received must be income and one paid must be a spend`() {
        val credit = txn(amount = "1500.00", type = TransactionType.CREDIT, merchant = "Asha Rao")
        assertEquals(SplitMatch.None, matcher.decide(settle(SplitEffect.SETTLE_OUT), listOf(credit)))
        assertEquals(SplitMatch.None, matcher.decide(settle(), listOf(credit.copy(nature = TransactionNature.REFUND))))
        assertEquals(SplitMatch.None, matcher.decide(settle(), listOf(credit.copy(transactedAt = credit.transactedAt.plusSeconds(3 * 86_400)))))
    }

    @Test
    fun `entries with nothing to match are a programming error`() {
        assertFailsWith<IllegalArgumentException> { matcher.decide(bill().copy(effect = SplitEffect.OWED_SPEND), emptyList()) }
        assertEquals(false, matcher.eligible(bill().copy(effect = SplitEffect.NONE), txn()))
    }
}
