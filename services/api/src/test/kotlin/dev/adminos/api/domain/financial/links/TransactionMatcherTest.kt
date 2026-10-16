package dev.adminos.api.domain.financial.links

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TransactionMatcherTest {

    private val matcher = TransactionMatcher()
    private val ist = ZoneId.of("Asia/Kolkata")
    private val user = UUID.randomUUID()

    private fun at(day: Int, h: Int = 9, m: Int = 12) = LocalDate.of(2026, 10, day).atTime(LocalTime.of(h, m)).atZone(ist).toInstant()

    private fun txn(source: String, day: Int, h: Int = 9, m: Int = 12, amount: String = "249.00", last4: String? = "1234",
                    vpa: String? = null, ref: String? = null, method: PaymentMethod? = PaymentMethod.UPI, merchant: String? = "Swiggy",
                    type: TransactionType = TransactionType.DEBIT) = Transaction(
        userId = user, sourceType = source, type = type, amount = BigDecimal(amount), accountLast4 = last4, upiVpa = vpa,
        paymentRef = ref, paymentMethod = method, merchantName = merchant, transactedAt = at(day, h, m)
    )
    private fun statementRow(day: Int, vararg mods: Pair<String, Any?>) = txn("statement", day, h = 12, m = 0).let { t ->
        mods.fold(t) { acc, (k, v) -> when (k) { "ref" -> acc.copy(paymentRef = v as String?); "last4" -> acc.copy(accountLast4 = v as String?)
            "method" -> acc.copy(paymentMethod = v as PaymentMethod?); "merchant" -> acc.copy(merchantName = v as String?); else -> acc } }
    }
    private fun decide(incoming: Transaction, vararg existing: Transaction, rejected: Set<UUID> = emptySet()) =
        matcher.decide(incoming, existing.map { MatchCandidate(it) }, rejected)

    @Test
    fun `same payment reference merges regardless of other fields`() {
        val sms = txn("sms", 1, ref = "123456789012", merchant = "SWIGGY")
        val d = assertIs<MatchDecision.AutoMerge>(decide(statementRow(2, "ref" to "123456789012", "merchant" to "Upi Swiggy Order"), sms))
        assertEquals("same payment reference", d.reason)
    }

    @Test
    fun `different payment references are never the same payment`() {
        assertEquals(MatchDecision.None, decide(statementRow(1, "ref" to "999999999999"), txn("sms", 1, ref = "123456789012")))
    }

    @Test
    fun `sms and statement row same day, account and merchant auto-merge`() {
        val d = assertIs<MatchDecision.AutoMerge>(decide(statementRow(1), txn("sms", 1)))
        assertEquals(12, d.score) // amount 4 + account 3 + same day 3 + merchant 2
    }

    @Test
    fun `conflicting accounts never match`() {
        assertEquals(MatchDecision.None, decide(statementRow(1, "last4" to "9999"), txn("sms", 1)))
    }

    @Test
    fun `different amount, direction or same source never match`() {
        assertEquals(MatchDecision.None, decide(statementRow(1), txn("sms", 1, amount = "249.01")))
        assertEquals(MatchDecision.None, decide(statementRow(1), txn("sms", 1, type = TransactionType.CREDIT)))
        assertEquals(MatchDecision.None, decide(txn("sms", 1, m = 13), txn("sms", 1)), "same source = two real payments")
    }

    @Test
    fun `upi window is plus or minus one day`() {
        assertIs<MatchDecision.AutoMerge>(decide(statementRow(2), txn("sms", 1, h = 23, m = 50)))
        assertEquals(MatchDecision.None, decide(statementRow(3), txn("sms", 1)))
    }

    @Test
    fun `card window allows the statement up to three days later but not two days earlier`() {
        val sms = txn("sms", 5, method = PaymentMethod.CARD_DEBIT, merchant = "Amazon")
        // Same amount + account + card window + similar merchant = 9: strong enough to merge.
        assertIs<MatchDecision.AutoMerge>(decide(statementRow(8, "method" to PaymentMethod.CARD_DEBIT, "merchant" to "Amazon Pay"), sms))
        // Without the account on one side the evidence is weaker (4 + 1 + 1 = 6): ask instead.
        assertIs<MatchDecision.Suggest>(decide(statementRow(8, "method" to PaymentMethod.CARD_DEBIT, "merchant" to "Amazon Pay", "last4" to null), sms))
        assertEquals(MatchDecision.None, decide(statementRow(9, "method" to PaymentMethod.CARD_DEBIT), sms))
        assertEquals(MatchDecision.None, decide(statementRow(3, "method" to PaymentMethod.CARD_DEBIT), sms))
    }

    @Test
    fun `sms vs email must be within ten minutes`() {
        assertIs<MatchDecision.AutoMerge>(decide(txn("gmail", 1, m = 20), txn("sms", 1, m = 12)))
        assertEquals(MatchDecision.None, decide(txn("gmail", 1, m = 30), txn("sms", 1, m = 12)))
    }

    @Test
    fun `two identical teas - an ambiguous pair is suggested, not auto-merged`() {
        val tea1 = txn("sms", 3, h = 9, amount = "10.00", merchant = "Tea Stall")
        val tea2 = txn("sms", 3, h = 17, amount = "10.00", merchant = "Tea Stall")
        val row = statementRow(3).copy(amount = BigDecimal("10.00"), merchantName = "Tea Stall")
        val d = assertIs<MatchDecision.Suggest>(decide(row, tea1, tea2))
        assertEquals(true, d.reason.contains("another similar"))
    }

    @Test
    fun `a transaction absorbs at most one record per source (1 to 1)`() {
        val sms = txn("sms", 3)
        val alreadyHasStatement = MatchCandidate(sms, mergedSources = setOf("statement"))
        assertEquals(MatchDecision.None, matcher.decide(statementRow(3), listOf(alreadyHasStatement)))
    }

    @Test
    fun `rejected pairs are not suggested again`() {
        val sms = txn("sms", 1)
        assertEquals(MatchDecision.None, decide(statementRow(1), sms, rejected = setOf(sms.id)))
    }

    @Test
    fun `manual entries are only ever suggested`() {
        assertIs<MatchDecision.Suggest>(decide(txn("manual", 1, h = 20, vpa = null), txn("sms", 1, h = 10)))
    }

    @Test
    fun `weak evidence stays separate`() {
        // different day within window, no account on one side, different merchant: 4 + 1 = 5 → suggest; without merchant info → suggest
        val sms = txn("sms", 1, last4 = null, merchant = "X")
        assertIs<MatchDecision.Suggest>(decide(statementRow(2, "merchant" to "Y"), sms))
    }

    @Test
    fun `beyond the hard seven-day limit nothing matches`() {
        assertEquals(MatchDecision.None, decide(txn("manual", 9), txn("sms", 1)))
    }
}
