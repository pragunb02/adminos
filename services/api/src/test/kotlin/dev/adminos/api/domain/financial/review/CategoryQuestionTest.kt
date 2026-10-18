package dev.adminos.api.domain.financial.review

import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionCategory.FOOD
import dev.adminos.api.domain.financial.TransactionCategory.HEALTH
import dev.adminos.api.domain.financial.TransactionCategory.OTHER
import dev.adminos.api.domain.financial.TransactionCategory.SHOPPING
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.learning.LearningFixtures.txn
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CategoryQuestionTest {
    private val me = UUID.randomUUID()

    private fun learned(status: String, category: String = "health", confidence: Any? = 0.55, extra: Map<String, Any?> = emptyMap()) =
        mapOf("learned" to mapOf("status" to status, "category" to category, "confidence" to confidence, "cues" to listOf("medicos"),
            "alternatives" to listOf("shopping", "health", "transfer", "nonsense"), "model" to "softmax-v1@abc") + extra)

    private fun ai(status: String, category: String = "food", confidence: Any? = 0.6) =
        mapOf("ai" to mapOf("status" to status, "category" to category, "confidence" to confidence, "reason" to "Bakery chain", "model" to "claude-haiku-5-5"))

    @Test
    fun `an unsure learned guess on a row still left as Other is a question`() {
        val q = CategoryQuestion.of(txn(me, "Sai Medicos", metadata = learned("suggested")))
        assertEquals(HEALTH, q?.guess?.category)
        assertEquals(GuessSource.LEARNED, q?.guess?.source)
        assertEquals(false, q?.guess?.applied)
        assertEquals(listOf("medicos"), q?.guess?.cues)
        assertEquals(listOf(SHOPPING), q?.guess?.alternatives, "the guess itself, Transfer and unknown names are dropped")
    }

    @Test
    fun `a category a model set is a question while nobody has touched it`() {
        val applied = txn(me, "Sai Medicos", HEALTH, "learned", metadata = learned("applied", confidence = 0.86))
        assertEquals(true, CategoryQuestion.of(applied)?.guess?.applied)
        assertNull(CategoryQuestion.of(applied.copy(categorySource = "user")), "the user answered")
        assertNull(CategoryQuestion.of(applied.copy(category = SHOPPING, categorySource = "remembered")), "a correction re-labelled it")
        val byAi = txn(me, "Bakers Dozen", FOOD, "ai", metadata = ai("applied", confidence = 0.8))
        assertEquals(GuessSource.AI, CategoryQuestion.of(byAi)?.guess?.source)
        assertEquals(true, CategoryQuestion.of(byAi)?.guess?.applied)
    }

    @Test
    fun `an AI suggestion carries its reason`() {
        val q = CategoryQuestion.of(txn(me, "Bakers Dozen", metadata = ai("suggested")))
        assertEquals("Bakery chain", q?.guess?.reason)
        assertEquals(FOOD, q?.guess?.category)
    }

    @Test
    fun `a suggestion is no longer a question once the row has a category`() {
        assertNull(CategoryQuestion.of(txn(me, "Sai Medicos", SHOPPING, "user", metadata = learned("suggested"))))
        assertNull(CategoryQuestion.of(txn(me, "Sai Medicos", OTHER, "user", metadata = learned("suggested"))), "answered Other")
        assertNull(CategoryQuestion.of(txn(me, "Sai Medicos", subcategory = "rent", metadata = learned("suggested"))))
    }

    @Test
    fun `rejected, Other and damaged guesses are ignored, never an error`() {
        assertNull(CategoryQuestion.of(txn(me, "X", metadata = ai("rejected"))))
        assertNull(CategoryQuestion.of(txn(me, "X", metadata = ai("other", category = "other"))))
        assertNull(CategoryQuestion.of(txn(me, "X", metadata = learned("suggested", category = "other"))))
        assertNull(CategoryQuestion.of(txn(me, "X", metadata = learned("suggested", category = "transfer"))))
        assertNull(CategoryQuestion.of(txn(me, "X", metadata = learned("suggested", category = "rent"))))
        assertNull(CategoryQuestion.of(txn(me, "X", metadata = learned("suggested", confidence = 1.5))))
        assertNull(CategoryQuestion.of(txn(me, "X", metadata = learned("suggested", confidence = "high"))))
        assertNull(CategoryQuestion.of(txn(me, "X", metadata = mapOf("learned" to "not a map"))))
        assertNull(CategoryQuestion.of(txn(me, "X")))
    }

    @Test
    fun `income and transfers are never asked about`() {
        assertNull(CategoryQuestion.of(txn(me, "Acme", type = TransactionType.CREDIT, metadata = learned("suggested"))))
    }

    @Test
    fun `of two guesses the more confident is asked, and a tie goes to the local model`() {
        val both = learned("suggested", confidence = 0.5) + ai("suggested", confidence = 0.6)
        assertEquals(GuessSource.AI, CategoryQuestion.of(txn(me, "X", metadata = both))?.guess?.source)
        val tie = learned("suggested", confidence = 0.6) + ai("suggested", confidence = 0.6)
        assertEquals(GuessSource.LEARNED, CategoryQuestion.of(txn(me, "X", metadata = tie))?.guess?.source)
    }

    @Test
    fun `numbers stored as decimals are read and the priority is uncertainty times amount`() {
        val q = CategoryQuestion.of(txn(me, "X", metadata = learned("suggested", confidence = BigDecimal("0.75"))).copy(amount = BigDecimal("2000.00")))
        assertEquals(0.75, q?.guess?.confidence)
        assertEquals(500.0, q?.priority ?: -1.0, 1e-9)
    }

    @Test
    fun `a guess record names a real category and a probability`() {
        assertFailsWith<IllegalArgumentException> { CategoryGuessRecord(OTHER, 0.5, GuessSource.AI, false) }
        assertFailsWith<IllegalArgumentException> { CategoryGuessRecord(TransactionCategory.TRANSFER, 0.5, GuessSource.AI, false) }
        assertFailsWith<IllegalArgumentException> { CategoryGuessRecord(FOOD, -0.1, GuessSource.AI, false) }
    }
}
