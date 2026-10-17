package dev.adminos.api.domain.ingestion.pipeline

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.InMemoryCategoryRuleRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.learning.CategoryModelCodec
import dev.adminos.api.domain.financial.learning.CategoryModelStore
import dev.adminos.api.domain.financial.learning.FixedModel
import dev.adminos.api.domain.financial.learning.InMemoryCategoryModelRepository
import dev.adminos.api.domain.financial.learning.LearnedCategorizer
import dev.adminos.api.domain.financial.learning.LearningFixtures
import dev.adminos.api.domain.financial.learning.ModelMode
import dev.adminos.api.domain.financial.learning.StoredCategoryModel
import dev.adminos.api.domain.financial.learning.TrainingSummary
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestSource
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** AOS-707: the learned step runs last, and only on rows every earlier step left as plain Other. */
class LearnedCategoryStepTest {
    private val me = UUID.randomUUID()
    private val store = CategoryModelStore(InMemoryCategoryModelRepository(), LearningFixtures.clock)
    private val rules = InMemoryCategoryRuleRepository()
    private val learned = LearnedCategorizer(store, rules, InMemoryTransactionRepository(), clock = LearningFixtures.clock)
    private val pipeline = IngestPipeline(listOf(
        ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()), CategorizeStep(CategorizationService(rules)), LearnedCategoryStep(learned)
    ))

    private suspend fun withSureHealthModel() {
        val e = CategoryModelCodec.encode(LearningFixtures.trainedModel().model)
        store.put(StoredCategoryModel(me, "softmax-v1", 1, e.bytes, e.checksum, ModelMode.APPLY, TrainingSummary(1, 0, 2, 0, null, null, 1.0),
            LearningFixtures.NOW), FixedModel(TransactionCategory.HEALTH, 0.95))
    }

    private fun event(raw: String, category: TransactionCategory? = null) = IngestEvent(
        userId = me, source = IngestSource.SMS, sourceRef = "ref-$raw", occurredAt = LearningFixtures.NOW,
        direction = IngestDirection.DEBIT, amount = BigDecimal("180.00"), merchantRaw = raw, paymentMethod = PaymentMethod.UPI,
        upiVpa = null, category = category
    )

    private suspend fun stored(raw: String, category: TransactionCategory? = null) =
        assertIs<PipelineOutcome.Created>(pipeline.run(event(raw, category))).transaction

    @Test
    fun `a payee the rules don't know gets the user's model's category`() = runBlocking<Unit> {
        withSureHealthModel()
        val t = stored("UPI-SAI MEDICOS-PAYMENT")
        assertEquals(TransactionCategory.HEALTH, t.category)
        assertEquals("learned", t.categorySource)
    }

    @Test
    fun `rules, catalogue brands and categories picked at entry are never changed`() = runBlocking<Unit> {
        withSureHealthModel()
        assertEquals("rules" to TransactionCategory.FOOD, stored("ZOMATO LTD").let { it.categorySource to it.category })
        assertEquals("user" to TransactionCategory.SHOPPING, stored("UPI-SAI MEDICOS-PAYMENT", TransactionCategory.SHOPPING).let { it.categorySource to it.category })
    }

    @Test
    fun `without a model the step changes nothing`() = runBlocking<Unit> {
        val t = stored("UPI-SAI MEDICOS-PAYMENT")
        assertEquals(TransactionCategory.OTHER, t.category)
        assertEquals("rules", t.categorySource)
        assertEquals(null, t.metadata)
    }
}
