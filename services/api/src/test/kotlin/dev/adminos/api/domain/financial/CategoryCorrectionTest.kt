package dev.adminos.api.domain.financial

import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-210: a correction becomes the payee's rule — used for new transactions and applied to past ones. */
class CategoryCorrectionTest {
    private val user = UUID.randomUUID()
    private val rules = InMemoryCategoryRuleRepository()
    private val txns = InMemoryTransactionRepository()
    private val categorizer = CategorizationService(rules)
    private val corrections = CategoryCorrectionService(rules, txns)

    private suspend fun saved(merchant: String?, vpa: String? = null, daysAgo: Long = 1, nature: TransactionNature = TransactionNature.SPEND,
                              source: String = "rules", category: TransactionCategory = TransactionCategory.OTHER) = txns.save(Transaction(
        userId = user, type = if (nature == TransactionNature.SPEND) TransactionType.DEBIT else TransactionType.CREDIT, nature = nature,
        amount = BigDecimal("120.00"), merchantName = merchant, upiVpa = vpa, category = category, categorySource = source,
        transactedAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS)
    ))

    @Test
    fun `a correction is remembered for the payee's UPI ID and used for new transactions`() = runBlocking<Unit> {
        val chai = saved("Sharma Tea Stall", vpa = "sharmatea@okaxis")
        val result = corrections.correct(chai, TransactionCategory.FOOD, "tea")
        assertTrue(result.remembered)
        assertEquals("user", result.transaction.categorySource)

        val next = Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal("30.00"), merchantName = "SHARMA T S",
            upiVpa = "SharmaTea@okaxis", transactedAt = Instant.now())
        val c = categorizer.categorize(next)
        assertEquals(TransactionCategory.FOOD, c.category); assertEquals("tea", c.subcategory); assertEquals("remembered", c.source)
    }

    @Test
    fun `without a UPI ID the merchant name is the key, and the UPI rule wins when both exist`() = runBlocking<Unit> {
        corrections.correct(saved("Corner Shop"), TransactionCategory.SHOPPING, null)
        corrections.correct(saved("Corner Shop", vpa = "corner@ybl"), TransactionCategory.FOOD, null)
        val byName = Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal.TEN, merchantName = "corner shop", transactedAt = Instant.now())
        assertEquals(TransactionCategory.SHOPPING, categorizer.categorize(byName).category)
        assertEquals(TransactionCategory.FOOD, categorizer.categorize(byName.copy(upiVpa = "corner@ybl")).category)
    }

    @Test
    fun `past spending and refunds from the payee are re-labelled, but not hand-set ones, transfers, or other users`() = runBlocking<Unit> {
        val past = saved("Gym Co", daysAgo = 30)
        val refund = saved("Gym Co", daysAgo = 20, nature = TransactionNature.REFUND)
        val handSet = saved("Gym Co", daysAgo = 10, source = "user", category = TransactionCategory.ENTERTAINMENT)
        val transfer = saved("Gym Co", daysAgo = 5, nature = TransactionNature.TRANSFER, category = TransactionCategory.TRANSFER)
        val stranger = txns.save(Transaction(userId = UUID.randomUUID(), type = TransactionType.DEBIT, amount = BigDecimal.ONE,
            merchantName = "Gym Co", transactedAt = Instant.now()))

        val result = corrections.correct(saved("Gym Co"), TransactionCategory.HEALTH, "gym")
        assertEquals(2, result.relabelled)
        assertEquals(TransactionCategory.HEALTH, txns.findById(past.id)!!.category)
        assertEquals("remembered", txns.findById(past.id)!!.categorySource)
        assertEquals(TransactionCategory.HEALTH, txns.findById(refund.id)!!.category)
        assertEquals(TransactionCategory.ENTERTAINMENT, txns.findById(handSet.id)!!.category)
        assertEquals(TransactionCategory.TRANSFER, txns.findById(transfer.id)!!.category)
        assertEquals(TransactionCategory.OTHER, txns.findById(stranger.id)!!.category)
    }

    @Test
    fun `remember false changes only this transaction`() = runBlocking<Unit> {
        val past = saved("Big Bazaar", daysAgo = 3)
        val result = corrections.correct(saved("Big Bazaar"), TransactionCategory.HEALTH, null, remember = false)
        assertFalse(result.remembered); assertEquals(0, result.relabelled)
        assertEquals(TransactionCategory.OTHER, txns.findById(past.id)!!.category)
        val next = Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal.TEN, merchantName = "Big Bazaar", transactedAt = Instant.now())
        assertEquals("rules", categorizer.categorize(next).source)
    }

    @Test
    fun `a correction replaces the earlier rule for the same payee`() = runBlocking<Unit> {
        corrections.correct(saved("Cafe X"), TransactionCategory.ENTERTAINMENT, null)
        corrections.correct(saved("Cafe X"), TransactionCategory.FOOD, "cafe")
        val next = Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal.TEN, merchantName = "Cafe X", transactedAt = Instant.now())
        assertEquals(TransactionCategory.FOOD, categorizer.categorize(next).category)
    }

    @Test
    fun `a payee with neither name nor UPI ID cannot be remembered`() = runBlocking<Unit> {
        val result = corrections.correct(saved(null), TransactionCategory.FOOD, null)
        assertFalse(result.remembered)
        assertEquals(TransactionCategory.FOOD, result.transaction.category)
    }
}
