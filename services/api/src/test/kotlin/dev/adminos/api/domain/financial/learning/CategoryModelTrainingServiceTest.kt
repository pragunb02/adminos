package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.InMemoryCategoryRuleRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.evaluation.GoldenSet
import dev.adminos.api.domain.financial.learning.LearningFixtures.CHAI
import dev.adminos.api.domain.financial.learning.LearningFixtures.KIRANAS
import dev.adminos.api.domain.financial.learning.LearningFixtures.NOW
import dev.adminos.api.domain.financial.learning.LearningFixtures.PHARMACIES
import dev.adminos.api.domain.financial.learning.LearningFixtures.TUTORS
import dev.adminos.api.domain.financial.learning.LearningFixtures.clock
import dev.adminos.api.domain.financial.learning.LearningFixtures.txn
import dev.adminos.api.domain.ingestion.pipeline.MerchantNormalizer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoryModelTrainingServiceTest {
    private val me = UUID.randomUUID()
    private val txns = InMemoryTransactionRepository()
    private val repo = InMemoryCategoryModelRepository()
    private val store = CategoryModelStore(repo, clock)
    private val categorizer = LearnedCategorizer(store, InMemoryCategoryRuleRepository(), txns, clock = clock)

    private fun service(prior: List<TrainingExample> = emptyList(), enabled: Boolean = true, policy: LearnedPolicy = LearnedPolicy()) =
        CategoryModelTrainingService(txns, store, categorizer, prior, enabled, policy, clock = clock)

    /** The user's own corrections: [perKind] payees of each kind of local shop. */
    private suspend fun corrections(perKind: Int) {
        listOf(PHARMACIES to TransactionCategory.HEALTH, KIRANAS to TransactionCategory.SHOPPING,
            TUTORS to TransactionCategory.EDUCATION, CHAI to TransactionCategory.FOOD).forEach { (names, category) ->
            names.take(perKind).forEach { txns.save(txn(me, it, category, "user")) }
        }
    }

    @Test
    fun `switched off, it never trains and says so`() = runBlocking<Unit> {
        corrections(10)
        val off = service(enabled = false)
        assertEquals(TrainingOutcome.Disabled, off.train(me))
        assertEquals(RetrainReport(TrainingOutcome.Disabled), off.retrain(me))
        assertEquals(ModelState.OFF, off.status(me).state)
        assertNull(repo.find(me))
        assertTrue(!off.isEnabled)
    }

    @Test
    fun `with too few corrections it stays off and reports how many more are needed`() = runBlocking<Unit> {
        corrections(4) // 16 payees, 20 needed
        val outcome = assertIs<TrainingOutcome.ColdStart>(service().train(me))
        assertEquals(ColdStartReason.TOO_FEW_CORRECTIONS, outcome.reason)
        assertEquals(16, outcome.corrections); assertEquals(20, outcome.needed)
        val status = service().status(me)
        assertEquals(ModelState.COLD_START, status.state)
        assertEquals(4, status.correctionsNeeded)
    }

    @Test
    fun `no corrections at all is a cold start, not an error`() = runBlocking<Unit> {
        val outcome = assertIs<TrainingOutcome.ColdStart>(service().train(me))
        assertEquals(0, outcome.corrections)
        assertEquals(RetrainReport(outcome), service().retrain(me))
    }

    @Test
    fun `corrections that all say one category teach nothing`() = runBlocking<Unit> {
        PHARMACIES.forEach { txns.save(txn(me, it, TransactionCategory.HEALTH, "user")) }
        val policy = LearnedPolicy(minCorrections = 5)
        assertEquals(ColdStartReason.ONE_CATEGORY, assertIs<TrainingOutcome.ColdStart>(service(policy = policy).train(me)).reason)
    }

    @Test
    fun `falling back below the minimum removes the model`() = runBlocking<Unit> {
        corrections(10)
        assertIs<TrainingOutcome.Trained>(service().train(me))
        assertNotNull(repo.find(me))
        val stricter = service(policy = LearnedPolicy(minCorrections = 100))
        assertIs<TrainingOutcome.ColdStart>(stricter.train(me))
        assertNull(repo.find(me))
        assertEquals(LoadedModel.None, store.load(me))
    }

    @Test
    fun `consistent corrections give a model trusted to apply, with its numbers stored`() = runBlocking<Unit> {
        corrections(10)
        val trained = assertIs<TrainingOutcome.Trained>(service().train(me)).model
        assertEquals(ModelMode.APPLY, trained.mode, trained.toString())
        assertEquals(40, trained.summary.corrections); assertEquals(40, trained.summary.trainingRows)
        assertEquals(4, trained.summary.categories); assertEquals(40, trained.summary.heldOut)
        assertTrue((trained.summary.heldOutPrecision ?: 0.0) >= 0.9)
        assertEquals(NOW, trained.trainedAt)
        assertEquals(trained, repo.find(me))
        val status = service().status(me)
        assertEquals(ModelState.READY, status.state); assertEquals(ModelMode.APPLY, status.mode); assertEquals(trained.summary, status.summary)
        assertEquals(0, status.correctionsNeeded)
    }

    @Test
    fun `corrections with no pattern give a model that only suggests`() = runBlocking<Unit> {
        val categories = listOf(TransactionCategory.HEALTH, TransactionCategory.SHOPPING, TransactionCategory.FOOD, TransactionCategory.EDUCATION)
        // Same kind of name, every category: nothing to learn, so held-out answers can't be trusted.
        (PHARMACIES + KIRANAS).forEachIndexed { i, name -> txns.save(txn(me, name, categories[i % 4], "user")) }
        val trained = assertIs<TrainingOutcome.Trained>(service().train(me)).model
        assertEquals(ModelMode.SUGGEST, trained.mode, trained.toString())
    }

    @Test
    fun `retraining re-scores recent Other rows`() = runBlocking<Unit> {
        corrections(10)
        val waiting = txns.save(txn(me, "Mahadev Medicos"))
        val report = service().retrain(me)
        assertIs<TrainingOutcome.Trained>(report.outcome)
        assertEquals(1, (report.backfill[LearnedOutcome.APPLIED] ?: 0) + (report.backfill[LearnedOutcome.SUGGESTED] ?: 0), report.toString())
        assertEquals("learned", txns.findById(waiting.id)?.metadata?.keys?.single())
    }

    @Test
    fun `a damaged stored model is reported as corrupt until retrained`() = runBlocking<Unit> {
        corrections(10)
        val trained = assertIs<TrainingOutcome.Trained>(service().train(me)).model
        val junk = "junk".toByteArray()
        repo.save(StoredCategoryModel(me, trained.algorithm, 1, junk, CategoryModelCodec.sha256(junk), trained.mode, trained.summary, NOW))
        val fresh = CategoryModelStore(repo, clock)
        val freshService = CategoryModelTrainingService(txns, fresh, LearnedCategorizer(fresh, InMemoryCategoryRuleRepository(), txns, clock = clock),
            emptyList(), enabled = true, clock = clock)
        assertEquals(ModelState.CORRUPT, freshService.status(me).state)
        assertIs<TrainingOutcome.Trained>(freshService.train(me))
        assertEquals(ModelState.READY, freshService.status(me).state)
    }

    @Test
    fun `with the synthetic prior, the model knows categories the user never corrected`() = runBlocking<Unit> {
        corrections(5)
        val prior = CategoryPrior.examples(GoldenSet.load(), MerchantNormalizer()::normalize)
        val trained = assertIs<TrainingOutcome.Trained>(service(prior).train(me)).model
        assertEquals(prior.size + 20, trained.summary.trainingRows)
        assertEquals(10, trained.summary.categories)
        assertNotNull(trained.summary.calibrationError)
    }

    @Test
    fun `two trainings for one user at once both finish, one after the other`() = runBlocking<Unit> {
        corrections(6)
        val s = service()
        val results = listOf(async { s.train(me) }, async { s.train(me) }).awaitAll()
        assertTrue(results.all { it is TrainingOutcome.Trained })
        assertEquals((results[1] as TrainingOutcome.Trained).model.checksum, repo.find(me)?.checksum)
    }

    @Test
    fun `the prior may hold only prior rows`() {
        assertFailsWith<IllegalArgumentException> { service(prior = listOf(LearningFixtures.example("x", TransactionCategory.FOOD))) }
    }
}
