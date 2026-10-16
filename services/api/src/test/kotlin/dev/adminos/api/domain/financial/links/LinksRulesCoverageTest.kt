package dev.adminos.api.domain.financial.links

import dev.adminos.api.InfraIngestTestKit.ist
import dev.adminos.api.InfraIngestTestKit.txn
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType.CREDIT
import dev.adminos.api.domain.financial.TransactionType.DEBIT
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Edge cases of the duplicate, transfer and refund/reversal rules (AOS-312). */
class LinksRulesCoverageTest {

    private val user = UUID.randomUUID()

    // ── TransactionMatcher ──

    private val matcher = TransactionMatcher()
    private fun c(t: Transaction, merged: Set<String> = emptySet()) = MatchCandidate(t, merged)
    private val sms = txn(user, DEBIT, at = ist(1, 9), source = "sms", last4 = "1234", merchant = "Swiggy")

    @Test
    fun `two equally good candidates are only suggested`() {
        val a = txn(user, DEBIT, at = ist(1, 9, 2), source = "gmail", last4 = "1234", merchant = "Swiggy")
        val b = a.copy(id = UUID.randomUUID())
        val d = matcher.decide(sms, listOf(c(a), c(b)))
        assertIs<MatchDecision.Suggest>(d)
        assertTrue("another similar transaction exists" in d.reason)
    }

    @Test
    fun `the same payment reference with a manual entry is only suggested`() {
        val manual = txn(user, DEBIT, at = ist(1, 10), source = "manual", ref = "123456789012")
        val d = matcher.decide(sms.copy(paymentRef = "123456789012"), listOf(c(manual)))
        assertIs<MatchDecision.Suggest>(d)
        assertTrue("manual entries are only suggested" in d.reason)
    }

    @Test
    fun `two candidates with the same reference are ambiguous`() {
        val a = txn(user, DEBIT, at = ist(1, 9), source = "gmail", ref = "123456789012")
        val d = matcher.decide(sms.copy(paymentRef = "123456789012"), listOf(c(a), c(a.copy(id = UUID.randomUUID()))))
        assertIs<MatchDecision.Suggest>(d)
    }

    @Test
    fun `a strong points match auto-merges`() {
        val gmail = txn(user, DEBIT, at = ist(1, 9, 3), source = "gmail", last4 = "1234", merchant = "Swiggy", vpa = "swiggy@icici")
        assertIs<MatchDecision.AutoMerge>(matcher.decide(sms.copy(upiVpa = "SWIGGY@icici"), listOf(c(gmail))))
    }

    @Test
    fun `hard rules exclude candidates`() {
        val base = txn(user, DEBIT, at = ist(1, 9, 1), source = "gmail", last4 = "1234", merchant = "Swiggy")
        val excluded = listOf(
            sms,                                              // the same transaction
            base.copy(userId = UUID.randomUUID()),            // someone else's
            base.copy(type = CREDIT),                         // other direction
            base.copy(amount = BigDecimal("250.00")),         // other amount
            base.copy(currency = "USD"),                      // other currency
            base.copy(sourceType = "sms"),                    // same source
            base.copy(paymentMethod = PaymentMethod.CASH),    // cash has no bank record
            base.copy(accountLast4 = "9999"),                 // conflicting account
            base.copy(paymentRef = "111111111111"),            // conflicting reference
            base.copy(transactedAt = ist(9, 9)),              // beyond the hard limit
            base.copy(transactedAt = ist(1, 10))              // two real-time sources 1 hour apart
        )
        for (e in excluded) assertEquals(MatchDecision.None, matcher.decide(sms.copy(paymentRef = if (e.paymentRef != null) "222222222222" else null), listOf(c(e))), e.toString())
        assertEquals(MatchDecision.None, matcher.decide(sms, listOf(c(base, merged = setOf("sms")))), "this source already merged in")
        assertEquals(MatchDecision.None, matcher.decide(sms, listOf(c(base)), rejectedWith = setOf(base.id)), "rejected by the user")
        assertEquals(MatchDecision.None, matcher.decide(sms.copy(paymentMethod = PaymentMethod.CASH), listOf(c(base))), "incoming cash")
    }

    @Test
    fun `statement windows depend on the payment method`() {
        val statementDay = { d: Int, m: PaymentMethod? -> txn(user, DEBIT, at = ist(d, 12), source = "statement", method = m) }
        val card = sms.copy(paymentMethod = PaymentMethod.CARD_CREDIT)
        assertIs<MatchDecision.Suggest>(matcher.decide(card, listOf(c(statementDay(4, null)))), "cards post up to 3 days later")
        assertEquals(MatchDecision.None, matcher.decide(card, listOf(c(statementDay(5, null)))))
        val neft = sms.copy(paymentMethod = PaymentMethod.NETBANKING)
        assertIs<MatchDecision.Suggest>(matcher.decide(neft, listOf(c(statementDay(3, null)))))
        assertEquals(MatchDecision.None, matcher.decide(neft, listOf(c(statementDay(4, null)))))
        assertEquals(MatchDecision.None, matcher.decide(sms, listOf(c(statementDay(3, null)))), "UPI: one day either side")
    }

    @Test
    fun `a statement row can be either side of the comparison`() {
        val statement = txn(user, DEBIT, at = ist(1, 12), source = "statement")
        assertIs<MatchDecision.Suggest>(matcher.decide(statement, listOf(c(sms))))
    }

    @Test
    fun `a manual entry is matched within two days`() {
        val manual = txn(user, DEBIT, at = ist(2, 20), source = "manual")
        assertIs<MatchDecision.Suggest>(matcher.decide(sms.copy(merchantName = null), listOf(c(manual))))
    }

    // ── TransferDetector ──

    private val transfers = TransferDetector()
    private val out = txn(user, DEBIT, amount = "5000.00", at = ist(1, 9), source = "sms", last4 = "1111")

    @Test
    fun `card bill rules never apply to a card purchase`() {
        assertFalse(transfers.isCardBillPayment(out.copy(merchantRaw = "CREDIT CARD BILL", paymentMethod = PaymentMethod.CARD_DEBIT)))
    }

    @Test
    fun `a next-day statement pair without hints is too weak`() {
        val inn = txn(user, CREDIT, amount = "5000.00", at = ist(2, 12), source = "statement", last4 = "2222")
        assertEquals(TransferDecision.None, transfers.decide(out, listOf(inn)))
    }

    @Test
    fun `two equally good counterparts are suggested, not applied`() {
        val inn = txn(user, CREDIT, amount = "5000.00", at = ist(1, 9, 2), source = "sms", last4 = "2222", raw = "IMPS TRANSFER")
        val d = transfers.decide(out, listOf(inn, inn.copy(id = UUID.randomUUID())))
        assertIs<TransferDecision.Pair>(d)
        assertFalse(d.auto)
        assertTrue("another similar transaction exists" in d.reason)
    }

    @Test
    fun `real-time pairs within an hour score lower, beyond an hour are not pairs`() {
        val inn = txn(user, CREDIT, amount = "5000.00", source = "gmail", last4 = "2222", raw = "NEFT FROM A/C XX1111")
        assertIs<TransferDecision.Pair>(transfers.decide(out, listOf(inn.copy(transactedAt = ist(1, 9, 40)))))
        assertEquals(TransferDecision.None, transfers.decide(out, listOf(inn.copy(transactedAt = ist(1, 11)))))
    }

    @Test
    fun `a non-real-time source is compared by day`() {
        val inn = txn(user, CREDIT, amount = "5000.00", at = ist(2, 9), source = "bank_api", last4 = "2222", raw = "SELF TRANSFER")
        assertIs<TransferDecision.Pair>(transfers.decide(out, listOf(inn)), "next day")
        assertEquals(TransferDecision.None, transfers.decide(out, listOf(inn.copy(transactedAt = ist(3, 9)))), "two days later")
    }

    @Test
    fun `ineligible counterparts are skipped`() {
        val inn = txn(user, CREDIT, amount = "5000.00", at = ist(1, 9, 1), source = "sms", last4 = "2222", raw = "IMPS TRANSFER")
        for (e in listOf(out, inn.copy(userId = UUID.randomUUID()), inn.copy(type = DEBIT), inn.copy(amount = BigDecimal("5001.00")),
            inn.copy(currency = "USD"), inn.copy(accountLast4 = null), inn.copy(accountLast4 = "1111"), inn.copy(nature = TransactionNature.TRANSFER))) {
            assertEquals(TransferDecision.None, transfers.decide(out, listOf(e)), e.toString())
        }
        assertEquals(TransferDecision.None, transfers.decide(out.copy(accountLast4 = null), listOf(inn)), "incoming without account")
    }

    // ── RefundReversalDetector ──

    private val refunds = RefundReversalDetector()
    private val paid = txn(user, DEBIT, amount = "999.00", at = ist(1, 9), last4 = "1234", merchant = "Amazon", raw = "POS AMAZON")

    private fun credit(amount: String = "999.00", day: Int = 3, merchant: String? = "Amazon", raw: String? = null, last4: String? = "1234", vpa: String? = null, ref: String? = null) =
        txn(user, CREDIT, amount = amount, at = ist(day, 9), last4 = last4, merchant = merchant, raw = raw, vpa = vpa, ref = ref)

    @Test
    fun `transfers are never refunds or reversals`() {
        assertEquals(RefundDecision.None, refunds.decide(paid.copy(nature = TransactionNature.TRANSFER), listOf(credit())))
    }

    @Test
    fun `ineligible candidates are skipped`() {
        for (c in listOf(paid, credit().copy(userId = UUID.randomUUID()), credit().copy(nature = TransactionNature.TRANSFER), paid.copy(id = UUID.randomUUID()))) {
            assertEquals(RefundDecision.None, refunds.decide(paid, listOf(c)), c.toString())
        }
        assertEquals(RefundDecision.None, refunds.decide(credit(), listOf(credit())), "two credits")
    }

    @Test
    fun `an incoming debit can be the original of an earlier-processed refund`() {
        val d = refunds.decide(paid, listOf(credit(raw = "REFUND AMAZON")))
        assertIs<RefundDecision.Link>(d)
        assertEquals(paid.id, d.original.id)
    }

    @Test
    fun `a matching reference and reversal narration make an automatic reversal`() {
        val d = refunds.decide(credit(day = 2, raw = "REV UPI 123456789012", ref = "123456789012"), listOf(paid.copy(paymentRef = "123456789012")))
        assertIs<RefundDecision.Link>(d)
        assertEquals(LinkRelation.REVERSAL, d.relation)
        assertTrue(d.auto)
        assertTrue("1 day(s) later" in d.reason, d.reason)
    }

    @Test
    fun `a same-amount credit days later without hints is not a reversal but may be a refund`() {
        val d = refunds.decide(credit(day = 4, merchant = "Amazon"), listOf(paid))
        assertIs<RefundDecision.Link>(d)
        assertEquals(LinkRelation.REFUND, d.relation)
    }

    @Test
    fun `a refund narration on a same-day same-amount credit is a refund, not a reversal`() {
        val d = refunds.decide(credit(day = 1, raw = "REFUND AMAZON"), listOf(paid))
        assertIs<RefundDecision.Link>(d)
        assertEquals(LinkRelation.REFUND, d.relation)
    }

    @Test
    fun `reversal and refund are both ruled out by time and accounts`() {
        assertEquals(RefundDecision.None, refunds.decide(credit().copy(transactedAt = ist(30, 9, month = 9)), listOf(paid)), "credit before the debit")
        assertEquals(RefundDecision.None, refunds.decide(credit(last4 = "9999"), listOf(paid)), "another account")
        assertEquals(RefundDecision.None, refunds.decide(txn(user, CREDIT, amount = "999.00", at = ist(1, 9).plus(java.time.Duration.ofDays(91)), merchant = "Amazon"), listOf(paid)), "beyond 90 days")
        assertEquals(RefundDecision.None, refunds.decide(credit(amount = "1000.00"), listOf(paid)), "more than was paid")
    }

    @Test
    fun `earlier refunds limit what can still be refunded`() {
        assertEquals(RefundDecision.None, refunds.decide(credit(amount = "500.00"), listOf(paid), mapOf(paid.id to BigDecimal("600.00"))))
        assertIs<RefundDecision.Link>(refunds.decide(credit(amount = "300.00", raw = "REFUND"), listOf(paid), mapOf(paid.id to BigDecimal("600.00"))))
    }

    @Test
    fun `a refund must come from a similar merchant`() {
        assertEquals(RefundDecision.None, refunds.decide(credit(amount = "100.00", merchant = "Flipkart"), listOf(paid)), "unrelated")
        val partial = refunds.decide(credit(amount = "100.00", merchant = "Amazon Seller Services", raw = "REFUND"), listOf(paid.copy(merchantName = "Amazon Pay India")))
        assertIs<RefundDecision.Link>(partial)
        assertTrue("similar merchant" in partial.reason && "partial" in partial.reason, partial.reason)
        val weak = refunds.decide(credit(amount = "100.00", merchant = "Amazon Seller Services Mumbai", raw = "REFUND", last4 = null),
            listOf(paid.copy(merchantName = "Amazon Retail Bengaluru Store")))
        assertIs<RefundDecision.Link>(weak, "one shared token of four is a weak but present match")
    }

    @Test
    fun `a refund from the same UPI handle counts as the same merchant`() {
        val d = refunds.decide(credit(amount = "100.00", merchant = "Ramu", vpa = "RAMU@ybl", raw = "REFUND"), listOf(paid.copy(merchantName = "Ramu Tea", upiVpa = "ramu@ybl")))
        assertIs<RefundDecision.Link>(d)
        assertTrue("same merchant" in d.reason)
    }

    @Test
    fun `a weak refund match is not suggested`() {
        assertEquals(RefundDecision.None, refunds.decide(credit(amount = "100.00", merchant = "Amazon Seller", last4 = null), listOf(paid.copy(merchantName = "Amazon Retail", accountLast4 = null))))
    }

    @Test
    fun `two similar originals make the refund ambiguous`() {
        val d = refunds.decide(credit(raw = "REFUND"), listOf(paid, paid.copy(id = UUID.randomUUID())))
        assertIs<RefundDecision.Link>(d)
        assertFalse(d.auto)
        assertTrue("another similar transaction exists" in d.reason)
    }
}
