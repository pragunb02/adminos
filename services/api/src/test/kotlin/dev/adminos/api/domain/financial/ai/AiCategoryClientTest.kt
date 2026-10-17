package dev.adminos.api.domain.financial.ai

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-512: what may be asked and answered, and the cache form (the circuit breaker is AiCircuitBreakerTest since AOS-702). */
class AiCategoryClientTest {

    private val question = AiCategoryQuestion("Steam", PaymentMethod.CARD_CREDIT, AI_ALLOWED_CATEGORIES)
    private val answer = AiCategoryAnswer(TransactionCategory.ENTERTAINMENT, 0.9, "PC game store")

    @Test
    fun `a question never carries a UPI ID, a long number or an empty name`() {
        assertFailsWith<IllegalArgumentException> { AiCategoryQuestion("rohit@oksbi", null, AI_ALLOWED_CATEGORIES) }
        assertFailsWith<IllegalArgumentException> { AiCategoryQuestion("Card 512345", null, AI_ALLOWED_CATEGORIES) }
        assertFailsWith<IllegalArgumentException> { AiCategoryQuestion(" ", null, AI_ALLOWED_CATEGORIES) }
        assertFailsWith<IllegalArgumentException> { AiCategoryQuestion("x".repeat(61), null, AI_ALLOWED_CATEGORIES) }
        assertFailsWith<IllegalArgumentException> { AiCategoryQuestion("Steam", null, emptyList()) }
        assertFailsWith<IllegalArgumentException> { AiCategoryQuestion("Steam", null, listOf(TransactionCategory.FOOD, TransactionCategory.FOOD)) }
    }

    @Test
    fun `the model may choose every category except Transfer`() {
        assertEquals(TransactionCategory.entries.size - 1, AI_ALLOWED_CATEGORIES.size)
        assertFalse(TransactionCategory.TRANSFER in AI_ALLOWED_CATEGORIES)
        assertTrue(TransactionCategory.OTHER in AI_ALLOWED_CATEGORIES)
    }

    @Test
    fun `an answer has a confidence from 0 to 1 and a short reason`() {
        assertFailsWith<IllegalArgumentException> { answer.copy(confidence = 1.01) }
        assertFailsWith<IllegalArgumentException> { answer.copy(confidence = -0.1) }
        assertFailsWith<IllegalArgumentException> { answer.copy(reason = "") }
        assertFailsWith<IllegalArgumentException> { answer.copy(reason = "x".repeat(201)) }
        assertEquals(1.0, answer.copy(confidence = 1.0).confidence)
    }

    @Test
    fun `answers and rejections are cached and unavailable results are not`() {
        val at = Instant.parse("2026-10-17T10:00:00Z")
        val cached = CachedAiCategory.of("STEAM", AiCategoryResult.Answered(answer, "m1"), "fallback", at)!!
        assertEquals("m1", cached.model)
        assertEquals(AiCategoryResult.Answered(answer, "m1"), cached.toResult())
        val rejected = CachedAiCategory.of("STEAM", AiCategoryResult.Rejected("bad"), "fallback", at)!!
        assertEquals("fallback", rejected.model)
        assertIs<AiCategoryResult.Rejected>(rejected.toResult())
        assertNull(CachedAiCategory.of("STEAM", AiCategoryResult.Unavailable("down"), "fallback", at))
        assertFailsWith<IllegalArgumentException> { cached.copy(merchantKey = " ") }
        assertFailsWith<IllegalArgumentException> { cached.copy(model = "") }
    }

    @Test
    fun `the in-memory cache stores and replaces by merchant key`() = runBlocking<Unit> {
        val cache = InMemoryAiCategoryCacheRepository()
        val at = Instant.parse("2026-10-17T10:00:00Z")
        assertNull(cache.find("STEAM"))
        cache.put(CachedAiCategory("STEAM", "m1", answer, at))
        cache.put(CachedAiCategory("STEAM", "m2", null, at))
        assertEquals("m2", cache.find("STEAM")?.model)
    }
}
