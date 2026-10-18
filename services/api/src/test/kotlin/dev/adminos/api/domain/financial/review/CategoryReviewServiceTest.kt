package dev.adminos.api.domain.financial.review

import dev.adminos.api.domain.audit.AuditActions
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.financial.CategoryCorrectionService
import dev.adminos.api.domain.financial.InMemoryCategoryRuleRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.RuleMatch
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.evaluation.GoldenSet
import dev.adminos.api.domain.financial.learning.LearningFixtures.NOW
import dev.adminos.api.domain.financial.learning.LearningFixtures.clock
import dev.adminos.api.domain.financial.learning.LearningFixtures.txn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoryReviewServiceTest {
    private val me = UUID.randomUUID()
    private val txns = InMemoryTransactionRepository()
    private val rules = InMemoryCategoryRuleRepository()
    private val auditLog = InMemoryAuditRepository()
    private val golden = InMemoryGoldenCandidateRepository()
    private val review = CategoryReviewService(txns, CategoryCorrectionService(rules, txns), golden,
        AuditService(auditLog, CoroutineScope(Dispatchers.Unconfined)), clock = clock)

    private fun suggested(confidence: Double, category: String = "health") =
        mapOf("learned" to mapOf("status" to "suggested", "category" to category, "confidence" to confidence, "model" to "m@1"))

    private suspend fun question(name: String, amount: String, confidence: Double, at: java.time.Instant = NOW.minusSeconds(3600)): Transaction =
        txns.save(txn(me, name, at = at, metadata = suggested(confidence)).copy(amount = BigDecimal(amount)))

    @Test
    fun `questions come biggest unsure spend first`() = runBlocking<Unit> {
        question("Small Unsure", "100.00", 0.4) // 60
        question("Big Fairly Sure", "5000.00", 0.9) // 500
        question("Big Unsure", "2000.00", 0.5) // 1000
        txns.save(txn(me, "Swiggy", TransactionCategory.FOOD)) // a rule decided: not a question
        assertEquals(listOf("Big Unsure", "Big Fairly Sure", "Small Unsure"), review.questions(me).map { it.transaction.merchantName })
        assertEquals(1, review.questions(me, limit = 1).size)
        assertFailsWith<IllegalArgumentException> { review.questions(me, limit = 0) }
    }

    @Test
    fun `only recent rows and only this user's are asked about`() = runBlocking<Unit> {
        question("Recent", "100.00", 0.5)
        question("Old", "100.00", 0.5, at = NOW.minus(Duration.ofDays(120)))
        txns.save(txn(UUID.randomUUID(), "Theirs", metadata = suggested(0.5)))
        assertEquals(listOf("Recent"), review.questions(me).map { it.transaction.merchantName })
    }

    @Test
    fun `equal priorities put the newest first`() = runBlocking<Unit> {
        question("Older", "100.00", 0.5, at = NOW.minusSeconds(7200))
        question("Newer", "100.00", 0.5, at = NOW.minusSeconds(60))
        assertEquals(listOf("Newer", "Older"), review.questions(me).map { it.transaction.merchantName })
    }

    @Test
    fun `an answer is a remembered correction that relabels the payee and leaves the queue`() = runBlocking<Unit> {
        val t = question("Sai Medicos", "240.00", 0.55)
        val sibling = txns.save(txn(me, "Sai Medicos", at = NOW.minusSeconds(86_400)))
        val answer = assertIs<ReviewAnswer.Answered>(review.answer(me, t.id, TransactionCategory.HEALTH, addToGolden = false))
        assertEquals(true, answer.correction.remembered)
        assertEquals(1, answer.correction.relabelled)
        assertNull(answer.golden)
        assertEquals("user", txns.findById(t.id)?.categorySource)
        assertEquals(TransactionCategory.HEALTH, txns.findById(sibling.id)?.category)
        assertEquals(TransactionCategory.HEALTH, rules.findFor(me, null, "sai medicos")?.category)
        assertEquals(RuleMatch.MERCHANT, rules.findFor(me, null, "sai medicos")?.match)
        assertEquals(emptyList(), review.questions(me))
        val entry = auditLog.findByUserId(me).single()
        assertEquals(AuditActions.TRANSACTION_UPDATE_CATEGORY, entry.action)
        assertTrue(entry.afterState.toString().contains("review"))
    }

    @Test
    fun `answering someone else's or a missing transaction is not found`() = runBlocking<Unit> {
        val theirs = txns.save(txn(UUID.randomUUID(), "Theirs", metadata = suggested(0.5)))
        assertEquals(ReviewAnswer.NotFound, review.answer(me, theirs.id, TransactionCategory.FOOD, addToGolden = true))
        assertEquals(ReviewAnswer.NotFound, review.answer(me, UUID.randomUUID(), TransactionCategory.FOOD, addToGolden = false))
        assertEquals(TransactionCategory.OTHER, txns.findById(theirs.id)?.category)
    }

    @Test
    fun `a scrubbed copy is added once, and a person's payment is refused`() = runBlocking<Unit> {
        val t = question("Sai Medicos", "240.00", 0.55)
        val first = assertIs<ReviewAnswer.Answered>(review.answer(me, t.id, TransactionCategory.HEALTH, addToGolden = true))
        assertEquals("UPI-SAI MEDICOS-PAYMENT", assertIs<GoldenOutcome.Added>(first.golden).candidate.merchantRaw)
        val again = assertIs<ReviewAnswer.Answered>(review.answer(me, t.id, TransactionCategory.HEALTH, addToGolden = true))
        assertEquals(GoldenOutcome.AlreadyThere, again.golden)
        val friend = question("Ravi Kumar", "500.00", 0.5)
        val refused = assertIs<ReviewAnswer.Answered>(review.answer(me, friend.id, TransactionCategory.OTHER, addToGolden = true))
        assertEquals(GoldenOutcome.Refused(ScrubRefusal.LOOKS_LIKE_A_PERSON), refused.golden)
        assertEquals(1, review.goldenSet(me).size)
    }

    @Test
    fun `the local golden set exports as golden-set CSV and rows can be removed`() = runBlocking<Unit> {
        val t = question("Sai Medicos", "240.00", 0.55)
        review.answer(me, t.id, TransactionCategory.HEALTH, addToGolden = true)
        val rows = review.goldenSet(me)
        val csv = CategoryReviewService.csv(rows)
        assertTrue(csv.startsWith("# AdminOS local golden set"))
        assertTrue("never commit" in csv)
        assertEquals(TransactionCategory.HEALTH, GoldenSet.parse(csv).single().expected.category)
        assertEquals(false, review.removeFromGoldenSet(UUID.randomUUID(), rows.single().id), "only the owner removes")
        assertEquals(true, review.removeFromGoldenSet(me, rows.single().id))
        assertEquals(emptyList(), review.goldenSet(me))
        assertEquals(false, review.removeFromGoldenSet(me, rows.single().id))
    }

    @Test
    fun `the settings are validated`() {
        assertFailsWith<IllegalArgumentException> { ReviewSettings(window = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { ReviewSettings(scanLimit = 0) }
        assertFailsWith<IllegalArgumentException> { ReviewSettings(maxQuestions = 0) }
    }
}
