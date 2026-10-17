package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.CategoryRule
import dev.adminos.api.domain.financial.InMemoryCategoryRuleRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.RuleMatch
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionCategory.HEALTH
import dev.adminos.api.domain.financial.TransactionCategory.OTHER
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.learning.LearningFixtures.NOW
import dev.adminos.api.domain.financial.learning.LearningFixtures.clock
import dev.adminos.api.domain.financial.learning.LearningFixtures.trainedModel
import dev.adminos.api.domain.financial.learning.LearningFixtures.txn
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A model that always answers the same: decision tests don't depend on training. */
class FixedModel(private val category: TransactionCategory, private val confidence: Double, private val cues: List<String> = listOf("medicos")) : CategoryModel {
    override val algorithm = "fixed"
    override val categories = listOf(HEALTH, OTHER, TransactionCategory.FOOD)
    override fun predict(input: CategoryInput) = CategoryGuess(category, confidence, cues)
    override fun distribution(input: CategoryInput) = categories.associateWith { if (it == category) confidence else (1 - confidence) / 2 }
}

class LearnedCategorizerTest {
    private val me = UUID.randomUUID()
    private val rules = InMemoryCategoryRuleRepository()
    private val txns = InMemoryTransactionRepository()
    private val repo = InMemoryCategoryModelRepository()
    private val store = CategoryModelStore(repo, clock)
    private val learned = LearnedCategorizer(store, rules, txns, LearnedPolicy(), clock = clock)

    private val encoded by lazy { CategoryModelCodec.encode(trainedModel().model) }

    private fun anyStored(mode: ModelMode) =
        StoredCategoryModel(me, "softmax-v1", 1, encoded.bytes, encoded.checksum, mode, TrainingSummary(1, 0, 2, 0, null, null, 1.0), NOW)

    private suspend fun withModel(model: CategoryModel, mode: ModelMode = ModelMode.APPLY) = store.put(anyStored(mode), model)

    @Suppress("UNCHECKED_CAST")
    private fun learnedOf(t: Transaction) = t.metadata?.get(LearnedCategorizer.METADATA_KEY) as Map<String, Any?>?

    @Test
    fun `only rows the rules left as plain Other are candidates`() {
        val other = txn(me, "Sai Medicos")
        assertTrue(learned.isCandidate(other))
        assertTrue(!learned.isCandidate(txn(me, "Swiggy", TransactionCategory.FOOD)), "a rule decided it")
        assertTrue(!learned.isCandidate(other.copy(categorySource = "user")), "a person decided it")
        assertTrue(!learned.isCandidate(other.copy(categorySource = "remembered")))
        assertTrue(!learned.isCandidate(other.copy(categorySource = "ai")), "another model decided it")
        assertTrue(!learned.isCandidate(other.copy(categorySource = "linked")))
        assertTrue(!learned.isCandidate(other.copy(subcategory = "rent")), "a deliberate Other")
        assertTrue(!learned.isCandidate(txn(me, "Paytm")), "a catalogue brand the rules keep as Other")
        assertTrue(!learned.isCandidate(other.copy(nature = TransactionNature.TRANSFER)))
        assertTrue(!learned.isCandidate(txn(me, "Acme", type = TransactionType.CREDIT)), "income")
        assertTrue(!learned.isCandidate(txn(me, null)), "nothing names the payee")
        assertTrue(learned.isCandidate(txn(me, null, vpa = "saimedicos@ybl")))
        assertTrue(learned.isCandidate(other.copy(type = TransactionType.REFUND, nature = TransactionNature.REFUND)))
    }

    @Test
    fun `a sure guess sets the category with source learned and records why`() = runBlocking<Unit> {
        withModel(FixedModel(HEALTH, 0.93))
        val d = learned.categorize(txn(me, "Sai Medicos", metadata = mapOf("mandate" to "umn-1")))
        assertEquals(LearnedOutcome.APPLIED, d.outcome)
        assertEquals(HEALTH, d.transaction.category)
        assertEquals("learned", d.transaction.categorySource)
        assertNull(d.transaction.subcategory)
        val record = learnedOf(d.transaction)
        assertEquals("applied", record?.get("status")); assertEquals("health", record?.get("category"))
        assertEquals(0.93, record?.get("confidence")); assertEquals(listOf("medicos"), record?.get("cues"))
        assertEquals(listOf("food", "other"), record?.get("alternatives"), "the next most likely, for one-tap corrections (AOS-708)")
        assertTrue((record?.get("model") as? String).orEmpty().startsWith("softmax-v1@")); assertEquals(NOW.toString(), record?.get("at"))
        assertEquals("umn-1", d.transaction.metadata?.get("mandate"), "other metadata is kept")
    }

    @Test
    fun `an unsure guess stays Other with the guess kept as a suggestion`() = runBlocking<Unit> {
        withModel(FixedModel(HEALTH, 0.55))
        val d = learned.categorize(txn(me, "Sai Medicos"))
        assertEquals(LearnedOutcome.SUGGESTED, d.outcome)
        assertEquals(OTHER, d.transaction.category)
        assertEquals("rules", d.transaction.categorySource, "the AI fallback may still look at it")
        assertEquals("suggested", learnedOf(d.transaction)?.get("status"))
    }

    @Test
    fun `a model not yet trusted only ever suggests`() = runBlocking<Unit> {
        withModel(FixedModel(HEALTH, 0.99), ModelMode.SUGGEST)
        assertEquals(LearnedOutcome.SUGGESTED, learned.categorize(txn(me, "Sai Medicos")).outcome)
    }

    @Test
    fun `a guess of Other, or one too unsure to suggest, writes nothing`() = runBlocking<Unit> {
        withModel(FixedModel(OTHER, 0.95))
        val t = txn(me, "Ravi Kumar")
        assertSame(t, learned.categorize(t).transaction)
        assertEquals(LearnedOutcome.UNSURE, learned.categorize(t).outcome)
        withModel(FixedModel(HEALTH, 0.2))
        assertEquals(LearnedDecision(t, LearnedOutcome.UNSURE), learned.categorize(t))
    }

    @Test
    fun `no model, or a damaged one, does nothing`() = runBlocking<Unit> {
        val t = txn(me, "Sai Medicos")
        assertEquals(LearnedDecision(t, LearnedOutcome.NO_MODEL), learned.categorize(t))
        repo.save(anyStored(ModelMode.APPLY).let { StoredCategoryModel(me, it.algorithm, 1, "junk".toByteArray(), CategoryModelCodec.sha256("junk".toByteArray()), it.mode, it.summary, NOW) })
        val fresh = LearnedCategorizer(CategoryModelStore(repo, clock), rules, txns, clock = clock)
        assertEquals(LearnedDecision(t, LearnedOutcome.NO_MODEL), fresh.categorize(t))
    }

    @Test
    fun `a payee with a remembered rule is never relabelled, even when the rule says Other`() = runBlocking<Unit> {
        withModel(FixedModel(HEALTH, 0.99))
        rules.upsert(CategoryRule(userId = me, match = RuleMatch.MERCHANT, value = "sai medicos", category = OTHER))
        assertEquals(LearnedOutcome.NOT_ELIGIBLE, learned.categorize(txn(me, "Sai Medicos")).outcome)
        rules.upsert(CategoryRule(userId = me, match = RuleMatch.VPA, value = "ravi@ybl", category = OTHER))
        assertEquals(LearnedOutcome.NOT_ELIGIBLE, learned.categorize(txn(me, null, vpa = "Ravi@ybl")).outcome)
    }

    @Test
    fun `a rule-decided row is never touched`() = runBlocking<Unit> {
        withModel(FixedModel(HEALTH, 0.99))
        val swiggy = txn(me, "Swiggy", TransactionCategory.FOOD)
        assertEquals(LearnedDecision(swiggy, LearnedOutcome.NOT_ELIGIBLE), learned.categorize(swiggy))
    }

    @Test
    fun `backfill labels recent Other rows, and a second run changes nothing`() = runBlocking<Unit> {
        withModel(FixedModel(HEALTH, 0.95))
        val recent = txns.save(txn(me, "Sai Medicos"))
        val old = txns.save(txn(me, "Om Medicos", at = NOW.minus(Duration.ofDays(60))))
        val ruled = txns.save(txn(me, "Swiggy", TransactionCategory.FOOD))
        assertEquals(mapOf(LearnedOutcome.APPLIED to 1), learned.backfill(me))
        assertEquals(HEALTH, txns.findById(recent.id)?.category)
        assertEquals(OTHER, txns.findById(old.id)?.category, "outside the window")
        assertEquals(ruled, txns.findById(ruled.id))
        assertEquals(emptyMap(), learned.backfill(me), "idempotent")
    }

    @Test
    fun `backfill skips rows already holding this model's answer`() = runBlocking<Unit> {
        withModel(FixedModel(HEALTH, 0.5))
        val t = txns.save(txn(me, "Sai Medicos"))
        assertEquals(mapOf(LearnedOutcome.SUGGESTED to 1), learned.backfill(me))
        val suggested = txns.findById(t.id)
        assertEquals(emptyMap(), learned.backfill(me))
        assertEquals(suggested, txns.findById(t.id))
    }

    @Test
    fun `backfill never overwrites a correction made meanwhile`() = runBlocking<Unit> {
        withModel(FixedModel(HEALTH, 0.95))
        val t = txns.save(txn(me, "Sai Medicos"))
        // The user corrects it between the read and the write.
        val racing = object : dev.adminos.api.domain.financial.TransactionRepository by txns {
            override suspend fun findById(id: UUID) = txns.findById(id)?.copy(category = TransactionCategory.SHOPPING, categorySource = "user")
        }
        val categorizer = LearnedCategorizer(store, rules, racing, clock = clock)
        assertEquals(mapOf(LearnedOutcome.NOT_ELIGIBLE to 1), categorizer.backfill(me))
        assertEquals(t, txns.findById(t.id))
    }

    @Test
    fun `backfill removes an older model's suggestion this model no longer stands behind`() = runBlocking<Unit> {
        withModel(FixedModel(HEALTH, 0.2))
        val old = mapOf("status" to "suggested", "category" to "health", "confidence" to 0.6, "model" to "softmax-v1@older")
        val t = txns.save(txn(me, "Sai Medicos", metadata = mapOf("learned" to old, "mandate" to "umn-1")))
        val bare = txns.save(txn(me, "Om Medicos", metadata = mapOf("learned" to old)))
        assertEquals(mapOf(LearnedOutcome.UNSURE to 2), learned.backfill(me))
        assertEquals(mapOf("mandate" to "umn-1"), txns.findById(t.id)?.metadata)
        assertNull(txns.findById(bare.id)?.metadata)
    }

    @Test
    fun `backfill without a model does nothing`() = runBlocking<Unit> {
        txns.save(txn(me, "Sai Medicos"))
        assertEquals(emptyMap(), learned.backfill(me))
    }

    @Test
    fun `the policy is validated`() {
        assertFailsWith<IllegalArgumentException> { LearnedPolicy(applyAt = 0.3, suggestAt = 0.5) }
        assertFailsWith<IllegalArgumentException> { LearnedPolicy(applyAt = 1.5) }
        assertFailsWith<IllegalArgumentException> { LearnedPolicy(minCorrections = 0) }
        assertFailsWith<IllegalArgumentException> { LearnedPolicy(minHeldOutPrecision = 2.0) }
        assertFailsWith<IllegalArgumentException> { LearnedPolicy(minHeldOutSure = 0) }
        assertFailsWith<IllegalArgumentException> { LearnedPolicy(trainingWindow = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { LearnedPolicy(maxTrainingRows = 0) }
        assertFailsWith<IllegalArgumentException> { LearnedPolicy(backfillWindow = Duration.ofDays(-1)) }
        assertFailsWith<IllegalArgumentException> { LearnedPolicy(backfillLimit = -1) }
    }

    @Test
    fun `the model that wrote a row's metadata is readable`() {
        assertNull(LearnedCategorizer.learnedModelOf(txn(me, "x")))
        assertNull(LearnedCategorizer.learnedModelOf(txn(me, "x", metadata = mapOf("learned" to "not a map"))))
        assertEquals("m@1", LearnedCategorizer.learnedModelOf(txn(me, "x", metadata = mapOf("learned" to mapOf("model" to "m@1")))))
    }
}
