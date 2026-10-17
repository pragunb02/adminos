package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.ai.AiCategorizationService
import dev.adminos.api.domain.financial.ai.AiCategoryAnswer
import dev.adminos.api.domain.financial.ai.AiCategoryCacheRepository
import dev.adminos.api.domain.financial.ai.AiCategoryClient
import dev.adminos.api.domain.financial.ai.AiCategoryResult
import dev.adminos.api.domain.financial.ai.AiOutcome
import dev.adminos.api.domain.financial.ai.CachedAiCategory
import dev.adminos.api.domain.financial.ai.InMemoryAiCategoryCacheRepository
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** AOS-512 on Postgres: the V28 answer cache, and an AI run whose result and metadata are stored. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiCategorizationIntegrationTest {

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private val at = Instant.parse("2026-10-17T10:00:00Z")

    /** The same checks for both implementations (Liskov). */
    private suspend fun roundTrips(cache: AiCategoryCacheRepository) {
        val key = "STEAM ${UUID.randomUUID()}".uppercase()
        assertNull(cache.find(key))
        val answer = AiCategoryAnswer(TransactionCategory.ENTERTAINMENT, 0.9, "PC game store")
        cache.put(CachedAiCategory(key, "claude-haiku-5-5", answer, at))
        assertEquals(CachedAiCategory(key, "claude-haiku-5-5", answer, at), cache.find(key))

        cache.put(CachedAiCategory(key, "claude-haiku-5-5", null, at.plusSeconds(60)))
        assertEquals(CachedAiCategory(key, "claude-haiku-5-5", null, at.plusSeconds(60)), cache.find(key), "a rejection replaces the entry")
    }

    @Test
    fun `the answer cache round-trips answers and rejections like the in-memory one`() = runBlocking<Unit> {
        roundTrips(ExposedAiCategoryCacheRepository())
        roundTrips(InMemoryAiCategoryCacheRepository())
    }

    @Test
    fun `an AI run on Postgres stores the category, source and the ai metadata`() = runBlocking<Unit> {
        val txns = ExposedTransactionRepository()
        val userId = PostgresTestDatabase.newUser()
        val merchant = "Steam ${UUID.randomUUID().toString().take(8).filter { it.isLetter() }}x"
        val t = txns.save(Transaction(userId = userId, type = TransactionType.DEBIT, amount = BigDecimal("899.00"), merchantName = merchant,
            merchantRaw = merchant.uppercase(), paymentMethod = PaymentMethod.CARD_CREDIT, transactedAt = at.truncatedTo(ChronoUnit.MILLIS),
            metadata = mapOf("mandate" to "umn-1")))
        val client = AiCategoryClient { _, _ -> AiCategoryResult.Answered(AiCategoryAnswer(TransactionCategory.ENTERTAINMENT, 0.91, "PC game store"), "claude-haiku-5-5") }
        val service = AiCategorizationService(client, ExposedAiCategoryCacheRepository(), ExposedCategoryRuleRepository(), txns, "claude-haiku-5-5")

        assertEquals(AiOutcome.APPLIED, service.categorize(t))
        val stored = txns.findById(t.id)!!
        assertEquals(TransactionCategory.ENTERTAINMENT, stored.category)
        assertEquals("ai", stored.categorySource)
        assertEquals("umn-1", stored.metadata?.get("mandate"), "other metadata is kept")
        @Suppress("UNCHECKED_CAST")
        val ai = stored.metadata?.get("ai") as Map<String, Any?>
        assertEquals("applied", ai["status"]); assertEquals("entertainment", ai["category"]); assertEquals(0.91, (ai["confidence"] as Number).toDouble())
        assertEquals(AiOutcome.NOT_ELIGIBLE, service.categorize(stored), "idempotent")
    }
}
