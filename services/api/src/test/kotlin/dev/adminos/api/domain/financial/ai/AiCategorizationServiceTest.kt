package dev.adminos.api.domain.financial.ai

import dev.adminos.api.domain.financial.CategoryRule
import dev.adminos.api.domain.financial.InMemoryCategoryRuleRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.RuleMatch
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-512, D-62: the AI fallback only fills in Other, never overrides rules, and asks each merchant once. */
class AiCategorizationServiceTest {

    private val now = Instant.parse("2026-10-17T10:00:00Z")
    private val clock = TestClock(now)
    private val user = UUID(0, 7)
    private val rules = InMemoryCategoryRuleRepository()
    private val txns = InMemoryTransactionRepository()
    private val cache = InMemoryAiCategoryCacheRepository()

    /** Answers from a table; unknown merchants are unavailable. Records every question. */
    private class FakeAi(private val answers: Map<String, AiCategoryResult>) : AiCategoryClient {
        val asked = mutableListOf<AiCategoryQuestion>()
        override suspend fun ask(userId: UUID, question: AiCategoryQuestion): AiCategoryResult {
            asked += question
            return answers[question.merchant] ?: AiCategoryResult.Unavailable("not scripted")
        }
    }

    private fun answered(category: TransactionCategory, confidence: Double, reason: String = "because") =
        AiCategoryResult.Answered(AiCategoryAnswer(category, confidence, reason), "claude-haiku-5-5")

    private val ai = FakeAi(mapOf(
        "Steam" to answered(TransactionCategory.ENTERTAINMENT, 0.9, "PC game store"),
        "Sapna Book House" to answered(TransactionCategory.EDUCATION, 0.6, "Bookshop"),
        "Edge Case Stores" to answered(TransactionCategory.SHOPPING, 0.7),
        "Mystery Labs" to answered(TransactionCategory.OTHER, 0.3),
        "Broken Labs" to AiCategoryResult.Rejected("not JSON"),
        "Transfer Labs" to answered(TransactionCategory.TRANSFER, 0.99)
    ))
    private val service = AiCategorizationService(ai, cache, rules, txns, "claude-haiku-5-5", clock = clock)

    private suspend fun saved(
        name: String?, method: PaymentMethod? = PaymentMethod.CARD_CREDIT, vpa: String? = null,
        category: TransactionCategory = TransactionCategory.OTHER, source: String = "rules",
        nature: TransactionNature = TransactionNature.SPEND, metadata: Map<String, Any?>? = null, raw: String? = name?.uppercase()
    ) = txns.save(Transaction(
        userId = user, type = TransactionType.DEBIT, nature = nature, amount = BigDecimal("499.00"), merchantName = name, merchantRaw = raw,
        paymentMethod = method, upiVpa = vpa, category = category, categorySource = source, metadata = metadata,
        transactedAt = now.minus(Duration.ofDays(1))
    ))

    @Suppress("UNCHECKED_CAST")
    private suspend fun aiOf(t: Transaction) = txns.findById(t.id)!!.metadata?.get("ai") as Map<String, Any?>?

    @Test
    fun `a sure answer recategorises with source ai and keeps the confidence and reason`() = runBlocking<Unit> {
        val t = saved("Steam")
        assertEquals(AiOutcome.APPLIED, service.categorize(t))
        val after = txns.findById(t.id)!!
        assertEquals(TransactionCategory.ENTERTAINMENT, after.category)
        assertEquals("ai", after.categorySource)
        assertNull(after.subcategory)
        assertEquals(mapOf("category" to "entertainment", "confidence" to 0.9, "reason" to "PC game store", "model" to "claude-haiku-5-5",
            "at" to now.toString(), "status" to "applied"), aiOf(t))
        assertEquals(listOf(AiCategoryQuestion("Steam", PaymentMethod.CARD_CREDIT, AI_ALLOWED_CATEGORIES)), ai.asked)
    }

    @Test
    fun `an unsure answer keeps Other and records the guess as a suggestion`() = runBlocking<Unit> {
        val t = saved("Sapna Book House", PaymentMethod.UPI)
        assertEquals(AiOutcome.SUGGESTED, service.categorize(t))
        val after = txns.findById(t.id)!!
        assertEquals(TransactionCategory.OTHER, after.category)
        assertEquals("rules", after.categorySource)
        assertEquals("suggested", aiOf(t)?.get("status"))
        assertEquals("education", aiOf(t)?.get("category"))
        assertEquals(0.6, aiOf(t)?.get("confidence"))
    }

    @Test
    fun `a confidence of exactly the threshold is applied`() = runBlocking<Unit> {
        assertEquals(0.7, AiCategorizationService.MIN_CONFIDENCE)
        assertEquals(AiOutcome.APPLIED, service.categorize(saved("Edge Case Stores", PaymentMethod.UPI)))
    }

    @Test
    fun `an Other answer, a rejected reply and a Transfer answer all leave the transaction as Other`() = runBlocking<Unit> {
        val other = saved("Mystery Labs")
        val broken = saved("Broken Labs")
        val transfer = saved("Transfer Labs")
        assertEquals(AiOutcome.KEPT_OTHER, service.categorize(other))
        assertEquals(AiOutcome.REJECTED, service.categorize(broken))
        assertEquals(AiOutcome.REJECTED, service.categorize(transfer))
        for (t in listOf(other, broken, transfer)) assertEquals(TransactionCategory.OTHER, txns.findById(t.id)!!.category)
        assertEquals("other", aiOf(other)?.get("status"))
        assertEquals("rejected", aiOf(broken)?.get("status"))
        assertEquals("rejected", aiOf(transfer)?.get("status"))
    }

    @Test
    fun `an unavailable model changes nothing, so the next run asks again`() = runBlocking<Unit> {
        val t = saved("Unscripted Labs")
        assertEquals(AiOutcome.UNAVAILABLE, service.categorize(t))
        assertEquals(t, txns.findById(t.id))
        assertNull(cache.find("UNSCRIPTED LABS"))
        assertEquals(AiOutcome.UNAVAILABLE, service.categorize(t))
        assertEquals(2, ai.asked.size)
    }

    @Test
    fun `each merchant is asked once, and a cached rejection is not asked again`() = runBlocking<Unit> {
        service.categorize(saved("Steam"))
        assertEquals(AiOutcome.APPLIED, service.categorize(saved("STEAM", raw = "STEAM GAMES")))
        service.categorize(saved("Broken Labs"))
        assertEquals(AiOutcome.REJECTED, service.categorize(saved("Broken Labs")))
        assertEquals(listOf("Steam", "Broken Labs"), ai.asked.map { it.merchant })
        assertEquals("claude-haiku-5-5", cache.find("BROKEN LABS")?.model)
    }

    @Test
    fun `a second run over the same transaction does nothing`() = runBlocking<Unit> {
        val t = saved("Sapna Book House", PaymentMethod.UPI)
        service.categorize(t)
        assertEquals(AiOutcome.NOT_ELIGIBLE, service.categorize(txns.findById(t.id)!!))
        assertEquals(1, ai.asked.size)
    }

    @Test
    fun `rules always win - the model is never asked when a person, a rule or the catalogue decided`() = runBlocking<Unit> {
        val notAsked = listOf(
            saved("Steam", source = "user"),                                  // the user chose Other
            saved("Steam", source = "remembered"),                            // a remembered rule said Other
            saved("Steam", category = TransactionCategory.SHOPPING),          // the rules found a category
            saved("Steam", nature = TransactionNature.INCOME),                // income has no payee category
            saved("Steam", nature = TransactionNature.TRANSFER),
            saved("Steam", metadata = mapOf("ai" to mapOf("status" to "suggested"))), // already asked
            saved("Paytm"),                                                   // a catalogue brand that is Other on purpose
            saved(null)
        )
        for (t in notAsked) assertEquals(AiOutcome.NOT_ELIGIBLE, service.categorize(t), "${t.categorySource} ${t.category} ${t.nature}")
        assertEquals(emptyList(), ai.asked)
    }

    @Test
    fun `a remembered rule for the UPI ID or the merchant name means the model is never asked`() = runBlocking<Unit> {
        rules.upsert(CategoryRule(userId = user, match = RuleMatch.VPA, value = "steam.store@icici", category = TransactionCategory.OTHER))
        rules.upsert(CategoryRule(userId = user, match = RuleMatch.MERCHANT, value = "sapna book house", category = TransactionCategory.OTHER))
        assertEquals(AiOutcome.NOT_ELIGIBLE, service.categorize(saved("Steam", vpa = "Steam.Store@icici")))
        assertEquals(AiOutcome.NOT_ELIGIBLE, service.categorize(saved("Sapna Book House", PaymentMethod.UPI)))
        assertEquals(emptyList(), ai.asked)
    }

    @Test
    fun `a payee that may not be shown is never asked about`() = runBlocking<Unit> {
        assertEquals(AiOutcome.PRIVATE, service.categorize(saved("Rohit Sharma", PaymentMethod.UPI, vpa = "rohit.sharma92@oksbi")))
        assertEquals(emptyList(), ai.asked)
    }

    @Test
    fun `a correction made while the model was answering is never overwritten`() = runBlocking<Unit> {
        val t = saved("Steam")
        val racing = AiCategorizationService(AiCategoryClient { _, _ ->
            txns.update(t.copy(category = TransactionCategory.SHOPPING, categorySource = "user"))
            answered(TransactionCategory.ENTERTAINMENT, 0.95)
        }, cache, rules, txns, "m", clock = clock)
        assertEquals(AiOutcome.NOT_ELIGIBLE, racing.categorize(t))
        assertEquals(TransactionCategory.SHOPPING, txns.findById(t.id)!!.category)
        assertEquals("user", txns.findById(t.id)!!.categorySource)
    }

    @Test
    fun `a transaction deleted while the model was answering is skipped`() = runBlocking<Unit> {
        val t = saved("Steam")
        val racing = AiCategorizationService(AiCategoryClient { _, _ -> txns.delete(t.id); answered(TransactionCategory.ENTERTAINMENT, 0.95) },
            cache, rules, txns, "m", clock = clock)
        assertEquals(AiOutcome.NOT_ELIGIBLE, racing.categorize(t))
    }

    @Test
    fun `the service validates its settings`() {
        assertFailsWith<IllegalArgumentException> { AiCategorizationService(ai, cache, rules, txns, "m", minConfidence = 1.5) }
        assertFailsWith<IllegalArgumentException> { AiCategorizationService(ai, cache, rules, txns, " ") }
    }

    // ── Job ──

    @Test
    fun `the job runs every 15 minutes over active users' recent Other rows and counts outcomes`() = runBlocking<Unit> {
        val users = InMemoryUserRepository()
        val pragun = users.save(User(id = user, email = "pragun@example.in", googleId = "g-ai-1"))
        users.save(User(email = "off@example.in", googleId = "g-ai-2", isActive = false))
        val applied = saved("Steam")
        saved("Sapna Book House", PaymentMethod.UPI)
        saved("Rohit Sharma", PaymentMethod.UPI, vpa = "rohit.sharma92@oksbi")
        saved("Netflix", category = TransactionCategory.SUBSCRIPTION) // not Other: not even read as a candidate
        val old = txns.save(applied.copy(id = UUID.randomUUID(), merchantName = "Steam Old", transactedAt = now.minus(Duration.ofDays(45))))

        val job = AiCategorizationJob(users, txns, service, clock)
        assertEquals(JobName("ai-categorization"), job.name)
        assertEquals(Schedule.Every(Duration.ofMinutes(15)), job.schedule)
        assertEquals(1, job.retry.maxAttempts)
        val counts = job.run().counts
        assertEquals(1, counts["users"]); assertEquals(0, counts["failedUsers"])
        assertEquals(1, counts["applied"]); assertEquals(1, counts["suggested"]); assertEquals(1, counts["private"])
        assertEquals(0, counts["unavailable"])
        assertEquals(TransactionCategory.OTHER, txns.findById(old.id)!!.category, "older than 30 days")
        assertTrue(pragun.isActive)

        val again = job.run().counts
        assertEquals(0, again["applied"]); assertEquals(0, again["suggested"]); assertEquals(1, again["private"])
        assertEquals(2, ai.asked.size, "nothing asked twice")
    }

    @Test
    fun `one user's failure is isolated and counted`() = runBlocking<Unit> {
        val users = InMemoryUserRepository()
        val broken = users.save(User(email = "broken@example.in", googleId = "g-ai-3"))
        users.save(User(id = user, email = "pragun@example.in", googleId = "g-ai-4"))
        saved("Steam")
        val failing = object : TransactionRepository by txns {
            override suspend fun findByUserId(
                userId: UUID, from: Instant?, to: Instant?, category: TransactionCategory?, type: TransactionType?, accountId: UUID?,
                isRecurring: Boolean?, merchantSearch: String?, cursor: String?, limit: Int, nature: TransactionNature?
            ): List<Transaction> = if (userId == broken.id) throw IOException("read timed out")
                else txns.findByUserId(userId, from, to, category, type, accountId, isRecurring, merchantSearch, cursor, limit, nature)
        }
        val counts = AiCategorizationJob(users, failing, service, clock).run().counts
        assertEquals(2, counts["users"]); assertEquals(1, counts["failedUsers"]); assertEquals(1, counts["applied"])
    }
}
