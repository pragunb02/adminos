package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.CategoryCorrectionService
import dev.adminos.api.domain.financial.InMemoryCategoryRuleRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AOS-511: the remembered-rules path (D-41), measured on the golden set. A user's corrections fix the
 * misses for those payees, for that user only, and never break a row that was right.
 */
class RememberedRulesEvaluationTest {

    private val golden = GoldenSet.load()
    private val owner = UUID(0, 1)
    private val someoneElse = UUID(0, 2)
    private val rules = InMemoryCategoryRuleRepository()
    private val txns = InMemoryTransactionRepository()
    private val categorizer = CategorizationService(rules)
    private val corrections = CategoryCorrectionService(rules, txns)

    // Misses of different kinds: an unknown brand (Steam), a UPI payee (Byju's, keyed by UPI ID),
    // a person's name that looks like a brand (Anil Ola → Ola), and a hospital known only by name (Fortis).
    private val corrected = listOf("N006", "D005", "O033", "H012")

    private fun case(id: String) = golden.single { it.id == id }

    private suspend fun evaluateFor(user: UUID) =
        CategorizationEvaluator.evaluate(PipelineLabeller(categorizer, userId = user).labelAll(golden))

    @Test
    fun `corrections fix the misses for those payees and break nothing`() = runBlocking<Unit> {
        val before = evaluateFor(owner)
        assertTrue(corrected.all { id -> before.misses.any { it.id == id } }, "the scenario must start from misses")

        for (id in corrected) {
            val c = case(id)
            val txn = txns.save(PipelineLabeller(categorizer, userId = owner).transactionFor(c))
            corrections.correct(txn, c.expected.category, c.expected.subcategory)
        }
        val after = evaluateFor(owner)

        assertEquals(before.correct + corrected.size, after.correct)
        assertTrue(corrected.none { id -> after.misses.any { it.id == id } })
        val newlyWrong = after.misses.map { it.id }.toSet() - before.misses.map { it.id }.toSet()
        assertEquals(emptySet(), newlyWrong)
        assertEquals(corrected.size, after.bySource["remembered"])
    }

    @Test
    fun `one user's corrections do not change another user's categories`() = runBlocking<Unit> {
        val baseline = evaluateFor(someoneElse)
        for (id in corrected) {
            val c = case(id)
            corrections.correct(txns.save(PipelineLabeller(categorizer, userId = owner).transactionFor(c)), c.expected.category, c.expected.subcategory)
        }
        assertEquals(baseline, evaluateFor(someoneElse))
    }

    @Test
    fun `a correction for one payee leaves other payees' misses alone`() = runBlocking<Unit> {
        val steam = case("N006")
        corrections.correct(txns.save(PipelineLabeller(categorizer, userId = owner).transactionFor(steam)), steam.expected.category, steam.expected.subcategory)
        val after = evaluateFor(owner)
        assertTrue(after.misses.none { it.id == "N006" })
        assertTrue(after.misses.any { it.id == "N007" }, "PlayStation is a different payee and stays a miss")
    }

    @Test
    fun `a correction keyed by a brand name relabels every row of that brand, the D-41 trade-off`() = runBlocking<Unit> {
        // "EMI 3 OF 12 AMAZON ORDER" has no UPI ID, so the rule is keyed by the merchant name "amazon":
        // every Amazon purchase without its own UPI rule becomes EMI too. Kept visible here on purpose (AOS-511 finding).
        val before = evaluateFor(owner)
        val emi = case("E003")
        corrections.correct(txns.save(PipelineLabeller(categorizer, userId = owner).transactionFor(emi)), emi.expected.category, emi.expected.subcategory)
        val after = evaluateFor(owner)
        assertTrue(after.misses.none { it.id == "E003" })
        val broken = after.misses.map { it.id }.toSet() - before.misses.map { it.id }.toSet()
        assertTrue(setOf("S001", "S002", "R001").all { it in broken }, "Amazon purchases now read as EMI: $broken")
    }
}
