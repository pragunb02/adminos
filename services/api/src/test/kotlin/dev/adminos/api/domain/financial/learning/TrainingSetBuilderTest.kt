package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.evaluation.GoldenSet
import dev.adminos.api.domain.financial.learning.LearningFixtures.NOW
import dev.adminos.api.domain.financial.learning.LearningFixtures.txn
import dev.adminos.api.domain.ingestion.pipeline.MerchantNormalizer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainingSetBuilderTest {
    private val me = UUID.randomUUID()

    @Test
    fun `corrections, remembered re-labels and real rule categories are learned from`() {
        assertEquals(ExampleOrigin.CORRECTION, TrainingSetBuilder.exampleOf(txn(me, "Sai Medicos", TransactionCategory.HEALTH, "user"))?.origin)
        assertEquals(ExampleOrigin.REMEMBERED, TrainingSetBuilder.exampleOf(txn(me, "Sai Medicos", TransactionCategory.HEALTH, "remembered"))?.origin)
        val rule = TrainingSetBuilder.exampleOf(txn(me, "Swiggy", TransactionCategory.FOOD, "rules"))
        assertEquals(ExampleOrigin.RULES, rule?.origin)
        assertEquals(true, rule?.easyForRules)
        assertEquals(false, TrainingSetBuilder.exampleOf(txn(me, "Sai Medicos", TransactionCategory.HEALTH, "user"))?.easyForRules)
    }

    @Test
    fun `a correction to Other is learned, but a row the rules merely left as Other is not`() {
        assertEquals(TransactionCategory.OTHER, TrainingSetBuilder.exampleOf(txn(me, "Ravi Kumar", TransactionCategory.OTHER, "user"))?.label)
        assertNull(TrainingSetBuilder.exampleOf(txn(me, "Ravi Kumar")))
        // A deliberate rule Other (a catalogue wallet) is a real label.
        assertEquals(TransactionCategory.OTHER, TrainingSetBuilder.exampleOf(txn(me, "Paytm", TransactionCategory.OTHER, "rules", subcategory = "wallet"))?.label)
    }

    @Test
    fun `model labels, links, transfers, income and anonymous rows are never learned from`() {
        assertNull(TrainingSetBuilder.exampleOf(txn(me, "Steam", TransactionCategory.ENTERTAINMENT, "ai")), "no feedback loop")
        assertNull(TrainingSetBuilder.exampleOf(txn(me, "Sai Medicos", TransactionCategory.HEALTH, "learned")), "no feedback loop")
        assertNull(TrainingSetBuilder.exampleOf(txn(me, "Swiggy", TransactionCategory.FOOD, "linked")))
        assertNull(TrainingSetBuilder.exampleOf(txn(me, "Own HDFC", TransactionCategory.TRANSFER, "user")))
        assertNull(TrainingSetBuilder.exampleOf(txn(me, "Acme Salary", TransactionCategory.OTHER, "user", type = TransactionType.CREDIT)))
        assertNull(TrainingSetBuilder.exampleOf(txn(me, null, TransactionCategory.FOOD, "user")))
        assertNull(TrainingSetBuilder.exampleOf(txn(me, " ", TransactionCategory.FOOD, "user")))
    }

    @Test
    fun `a payee with no name is keyed by its UPI ID`() {
        assertEquals("user:ramu@ybl", TrainingSetBuilder.exampleOf(txn(me, null, TransactionCategory.FOOD, "user", vpa = " Ramu@YBL "))?.key)
        assertEquals("user:SAI MEDICOS", TrainingSetBuilder.exampleOf(txn(me, "Sai  Medicos!", TransactionCategory.HEALTH, "user"))?.key)
    }

    @Test
    fun `a payee counts once, the user's own choice winning over a rule and then the latest`() {
        val rows = listOf(
            txn(me, "Amazon", TransactionCategory.SHOPPING, "rules", at = NOW.minusSeconds(100)),
            txn(me, "Amazon", TransactionCategory.EMI, "user", at = NOW.minusSeconds(500)),
            txn(me, "Sai Medicos", TransactionCategory.SHOPPING, "user", at = NOW.minusSeconds(900)),
            txn(me, "Sai Medicos", TransactionCategory.HEALTH, "user", at = NOW.minusSeconds(50))
        )
        val set = TrainingSetBuilder.build(rows, emptyList())
        assertEquals(mapOf("user:AMAZON" to TransactionCategory.EMI, "user:SAI MEDICOS" to TransactionCategory.HEALTH),
            set.examples.associate { it.key to it.label })
        assertEquals(2, set.corrections)
    }

    @Test
    fun `refunds are learned like spending`() {
        val refund = txn(me, "Sai Medicos", TransactionCategory.HEALTH, "user").copy(type = TransactionType.REFUND, nature = TransactionNature.REFUND)
        assertEquals(ExampleOrigin.CORRECTION, TrainingSetBuilder.exampleOf(refund)?.origin)
    }

    @Test
    fun `the synthetic prior is appended, without transfers, and marks the rows the rules decide`() {
        val prior = CategoryPrior.examples(GoldenSet.load(), MerchantNormalizer()::normalize)
        assertTrue(prior.isNotEmpty() && prior.all { CategoryPrior.isPrior(it.key) && it.origin == ExampleOrigin.PRIOR })
        assertTrue(prior.none { it.label == TransactionCategory.TRANSFER })
        assertTrue(prior.any { it.easyForRules } && prior.any { !it.easyForRules })
        val set = TrainingSetBuilder.build(listOf(txn(me, "Sai Medicos", TransactionCategory.HEALTH, "user")), prior)
        assertEquals(prior.size + 1, set.examples.size)
        assertEquals(1, set.corrections)
        assertTrue(set.categories.size >= 10)
    }
}
