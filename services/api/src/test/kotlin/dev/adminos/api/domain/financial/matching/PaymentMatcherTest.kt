package dev.adminos.api.domain.financial.matching

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionStatus
import dev.adminos.api.domain.financial.TransactionType
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** AOS-903: the reusable payment matcher (D-38 ideas: hard rules, points, bands, ambiguity). */
class PaymentMatcherTest {
    private val matcher = PaymentMatcher()
    private val user = UUID.randomUUID()
    private val orderAt = Instant.parse("2026-10-17T16:10:00Z") // 21:40 IST
    private val window = PaymentWindow(Duration.ofMinutes(15), Duration.ofHours(3), 0..1, -1..3)

    private fun claim(amount: String = "487.00", payee: String = "Swiggy", method: PaymentMethod? = PaymentMethod.UPI, vararg more: String) =
        PaymentClaim(user, listOf(BigDecimal(amount)) + more.map(::BigDecimal), orderAt, window, payee, method)

    private fun payment(
        amount: String = "487.00", at: Instant = orderAt.plusSeconds(60), merchant: String? = "Swiggy", source: String = "sms",
        method: PaymentMethod? = PaymentMethod.UPI, raw: String? = null, vpa: String? = null
    ) = Transaction(userId = user, sourceType = source, type = TransactionType.DEBIT, amount = BigDecimal(amount), merchantName = merchant,
        merchantRaw = raw, upiVpa = vpa, paymentMethod = method, transactedAt = at)

    @Test
    fun `the exact amount to the app within minutes is linked`() {
        val p = payment()
        val d = assertIs<PaymentMatch.Auto>(matcher.decide(claim(), listOf(p)))
        assertEquals(p.id, d.payment.id)
        assertEquals(12, d.score)
        assertTrue("paid to Swiggy" in d.reason && "within 30 minutes" in d.reason && "same payment method" in d.reason)
    }

    @Test
    fun `the brand family counts as evidence`() {
        val c = PaymentClaim(user, listOf(BigDecimal("189.80")), orderAt, window, "Swiggy Instamart", null)
        assertIs<PaymentMatch.Auto>(matcher.decide(c, listOf(payment("189.80", merchant = null, raw = "UPI-BUNDL SWIGGY-HDFC"))))
        assertIs<PaymentMatch.Auto>(matcher.decide(c, listOf(payment("189.80", merchant = null, vpa = "swiggy.payu@hdfcbank"))))
        assertIs<PaymentMatch.Suggest>(matcher.decide(c, listOf(payment("189.80", merchant = "Swiggyx"))), "a longer word isn't the brand")
    }

    @Test
    fun `without payee evidence a match is only suggested`() {
        val d = assertIs<PaymentMatch.Suggest>(matcher.decide(claim(), listOf(payment(merchant = "Ravi Kumar"))))
        assertEquals(8, d.score)
        assertTrue("doesn't name Swiggy" in d.reason)
    }

    @Test
    fun `hard rules - amount, direction, nature, status, cash, user and exclusions`() {
        val c = claim()
        val rejected = payment()
        listOf(
            payment("487.01"),
            payment().copy(type = TransactionType.CREDIT),
            payment().copy(nature = TransactionNature.TRANSFER),
            payment().copy(nature = TransactionNature.REFUND),
            payment().copy(status = TransactionStatus.FAILED),
            payment().copy(status = TransactionStatus.REVERSED),
            payment(method = PaymentMethod.CASH),
            payment().copy(userId = UUID.randomUUID())
        ).forEach { assertEquals(PaymentMatch.None, matcher.decide(c, listOf(it)), "$it") }
        assertEquals(PaymentMatch.None, matcher.decide(c, listOf(rejected), excluded = setOf(rejected.id)))
    }

    @Test
    fun `real-time window - 15 minutes before to 3 hours after`() {
        val c = claim()
        assertIs<PaymentMatch.Auto>(matcher.decide(c, listOf(payment(at = orderAt.minus(Duration.ofMinutes(15))))))
        assertEquals(PaymentMatch.None, matcher.decide(c, listOf(payment(at = orderAt.minus(Duration.ofMinutes(16))))))
        val late = assertIs<PaymentMatch.Auto>(matcher.decide(c, listOf(payment(at = orderAt.plus(Duration.ofHours(3))))))
        assertEquals(11, late.score, "further than 30 minutes is 2 time points")
        assertEquals(PaymentMatch.None, matcher.decide(c, listOf(payment(at = orderAt.plus(Duration.ofHours(3)).plusSeconds(1)))))
    }

    @Test
    fun `statement rows are matched by Indian date, cards posting up to 3 days later`() {
        val c = claim(method = null)
        val sameDay = payment(at = Instant.parse("2026-10-16T18:30:00Z"), source = "statement", method = null) // 00:00 IST on the 17th
        assertEquals(11, assertIs<PaymentMatch.Auto>(matcher.decide(c, listOf(sameDay))).score)
        val nextDay = payment(at = Instant.parse("2026-10-17T18:30:00Z"), source = "statement", method = null)
        assertEquals(9, assertIs<PaymentMatch.Auto>(matcher.decide(c, listOf(nextDay))).score)
        assertEquals(PaymentMatch.None, matcher.decide(c, listOf(payment(at = Instant.parse("2026-10-18T18:30:00Z"), source = "statement", method = null))))
        val card = payment(at = Instant.parse("2026-10-20T06:00:00Z"), source = "statement", method = PaymentMethod.CARD_CREDIT)
        assertIs<PaymentMatch.Auto>(matcher.decide(c, listOf(card)))
        val cardEarly = payment(at = Instant.parse("2026-10-16T06:00:00Z"), source = "manual", method = PaymentMethod.CARD_DEBIT)
        assertIs<PaymentMatch.Auto>(matcher.decide(c, listOf(cardEarly)))
        assertEquals(PaymentMatch.None, matcher.decide(c, listOf(payment(at = Instant.parse("2026-10-21T06:00:00Z"), source = "statement", method = PaymentMethod.CARD_CREDIT))))
    }

    @Test
    fun `two equally good payments are only suggested, the nearer first`() {
        val near = payment(at = orderAt.plusSeconds(30))
        val far = payment(at = orderAt.plusSeconds(600))
        val d = assertIs<PaymentMatch.Suggest>(matcher.decide(claim(), listOf(far, near)))
        assertEquals(near.id, d.payment.id)
        assertTrue("another similar payment" in d.reason)
    }

    @Test
    fun `a clear winner beats a weaker runner-up`() {
        val strong = payment()
        val weak = payment(merchant = "Someone")
        assertEquals(strong.id, assertIs<PaymentMatch.Auto>(matcher.decide(claim(), listOf(weak, strong, strong))).payment.id)
    }

    @Test
    fun `an alternative amount is accepted, a card claim matches either card type`() {
        val c = claim("350.00", "Swiggy", PaymentMethod.CARD_CREDIT, "450.00")
        assertEquals(12, assertIs<PaymentMatch.Auto>(matcher.decide(c, listOf(payment("450.00", method = PaymentMethod.CARD_DEBIT)))).score)
        assertEquals(11, assertIs<PaymentMatch.Auto>(matcher.decide(c, listOf(payment("350.00", method = PaymentMethod.UPI)))).score)
    }

    @Test
    fun `nothing to match on is no match, and the weakest match is a suggestion`() {
        assertEquals(PaymentMatch.None, matcher.decide(claim(), emptyList()))
        val weakest = payment(at = Instant.parse("2026-10-17T18:30:00Z"), source = "statement", method = null, merchant = "Ravi")
        assertEquals(PaymentMatcher.SUGGEST_AT, assertIs<PaymentMatch.Suggest>(matcher.decide(claim(method = null), listOf(weakest))).score,
            "the weakest eligible payment (date-only, next day, no payee) is still worth a suggestion")
    }

    @Test
    fun `a short payee word is never evidence`() {
        assertEquals(false, matcher.namesPayee(payment(merchant = "Vi"), "Vi"))
    }

    @Test
    fun `claims and windows validate themselves`() {
        assertFailsWith<IllegalArgumentException> { PaymentClaim(user, emptyList(), orderAt, window, "Swiggy") }
        assertFailsWith<IllegalArgumentException> { PaymentClaim(user, listOf(BigDecimal.ZERO), orderAt, window, "Swiggy") }
        assertFailsWith<IllegalArgumentException> { PaymentClaim(user, listOf(BigDecimal.ONE), orderAt, window, " ") }
        assertFailsWith<IllegalArgumentException> { PaymentWindow(Duration.ofMinutes(-1), Duration.ZERO, 0..1, 0..1) }
        assertFailsWith<IllegalArgumentException> { PaymentWindow(Duration.ZERO, Duration.ofMinutes(-1), 0..1, 0..1) }
        assertFailsWith<IllegalArgumentException> { PaymentWindow(Duration.ZERO, Duration.ZERO, 1..0, 0..1) }
        assertFailsWith<IllegalArgumentException> { PaymentWindow(Duration.ZERO, Duration.ZERO, 0..1, 1..0) }
    }
}
