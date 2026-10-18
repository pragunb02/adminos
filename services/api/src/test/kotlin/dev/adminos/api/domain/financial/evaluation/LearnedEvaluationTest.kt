package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.learning.CategoryInput
import dev.adminos.api.domain.financial.learning.CategoryModelFitter
import dev.adminos.api.domain.financial.learning.CategoryPrior
import dev.adminos.api.domain.financial.learning.ExampleOrigin
import dev.adminos.api.domain.financial.learning.LearnedOutcome
import dev.adminos.api.domain.financial.learning.SoftmaxRegressionTrainer
import dev.adminos.api.domain.financial.learning.TrainingExample
import dev.adminos.api.domain.ingestion.pipeline.MerchantNormalizer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AOS-707 on the D-61 golden set, cross-validated (no row is scored by a model that saw it):
 * the learned step may only add right answers to rows the rules left as Other.
 */
class LearnedEvaluationTest {
    /** Computed once for the class: cross-validation trains 30 models, and coverage instrumentation makes that slow. */
    private companion object {
        val golden by lazy { GoldenSet.load() }
        val rules by lazy { rulesReport() }
        val run by lazy { rulesLearnedRun() }
        val learned by lazy { CategorizationEvaluator.evaluate(run.outcomes) }
    }

    @Test
    fun `rules plus learned scores no lower than its baseline`() {
        val baseline = Baselines.load(Baselines.RULES_LEARNED)
        assertEquals(baseline.goldenRows, learned.total, "The golden set changed size: re-record with -PwriteBaseline")
        val drops = RegressionGate.regressions(learned, baseline)
        if (drops.isNotEmpty()) fail("Rules + learned got worse on the golden set:\n" + drops.joinToString("\n") { "  - $it" })
    }

    @Test
    fun `it beats the rules on accuracy and macro-F1`() {
        assertTrue(learned.accuracy > rules.accuracy, "${learned.accuracy} vs ${rules.accuracy}")
        assertTrue(learned.macroF1 >= rules.macroF1)
        assertTrue(RegressionGate.regressions(learned, Baselines.load(Baselines.RULES)).isEmpty(), "never below the rules' floor")
    }

    @Test
    fun `no category's precision drops below the rules'`() {
        val drops = learned.perCategory.mapNotNull { s ->
            val r = rules.scoreOf(s.category) ?: return@mapNotNull null
            if (r.predicted > 0 && s.precision < r.precision - 1e-9) "${s.category} ${r.precision} → ${s.precision}" else null
        }
        assertEquals(emptyList(), drops)
    }

    @Test
    fun `it never changes a row the rules decided`() = runBlocking<Unit> {
        val byRules = PipelineLabeller().labelAll(golden).associateBy { it.case.id }
        val changed = run.outcomes.filter { it.predicted != byRules.getValue(it.case.id).predicted }
        assertTrue(changed.isNotEmpty())
        for (o in changed) {
            val before = byRules.getValue(o.case.id).predicted
            assertEquals(Prediction(Label(TransactionCategory.OTHER), "rules"), before, "${o.case.id} was decided by the rules")
            assertEquals("learned", o.predicted.source)
        }
    }

    @Test
    fun `every label it applied on the golden set is right`() {
        val applied = run.answers.filter { it.outcome == LearnedOutcome.APPLIED }
        assertTrue(applied.isNotEmpty())
        assertEquals(applied.size, applied.count { it.correct }, applied.filterNot { it.correct }.joinToString { it.caseId })
    }

    @Test
    fun `payments to people are never relabelled`() {
        val p2p = run.outcomes.filter { "p2p" in it.case.tags }
        assertTrue(p2p.isNotEmpty())
        assertTrue(p2p.none { it.predicted.source == "learned" }, p2p.filter { it.predicted.source == "learned" }.joinToString { it.case.id })
    }

    @Test
    fun `held-out confidence is calibrated to within a tenth`() {
        val ece = run.calibrationError ?: fail("nothing was held out")
        assertTrue(ece <= 0.1, "expected calibration error $ece")
    }

    @Test
    fun `it learns from corrections, the more the better`() {
        // A simulated stream of corrections for kinds of shop the golden set (the prior) has never seen; then new shops of each kind.
        val kinds = mapOf(
            "Opticals" to TransactionCategory.HEALTH, "Footwear" to TransactionCategory.SHOPPING,
            "Dairy" to TransactionCategory.FOOD, "Turf" to TransactionCategory.ENTERTAINMENT
        )
        val owners = listOf("Ganesh", "Laxmi", "Royal", "Star", "Moti", "Kaveri", "Nandi", "Sagar", "Tirupati", "Vinayak")
        val asked = kinds.map { (kind, category) -> "Shiva $kind" to category }
        val prior = CategoryPrior.examples(golden, MerchantNormalizer()::normalize)
        fun rightAfter(k: Int): Int {
            val corrections = kinds.flatMap { (kind, category) ->
                owners.take(k).map { TrainingExample("user:$it $kind", CategoryInput(merchantName = "$it $kind"), category, ExampleOrigin.CORRECTION) }
            }
            val model = CategoryModelFitter(SoftmaxRegressionTrainer()).fit(prior + corrections).model
            return asked.count { (name, category) -> model.predict(CategoryInput(merchantName = name)).category == category }
        }
        val curve = listOf(0, 2, 8).map(::rightAfter)
        assertTrue(curve[0] < curve[2], "learning curve $curve")
        assertTrue(curve.zipWithNext().all { (a, b) -> b >= a }, "never worse with more corrections: $curve")
        assertEquals(asked.size, curve[2], "learning curve $curve")
    }

    @Test
    fun `a local golden set exported from Review is scored with a model trained on the whole shipped set`() = runBlocking<Unit> {
        val header = "id,merchant_raw,upi_vpa,payment_method,direction,expected_category,expected_subcategory,tags"
        val local = GoldenSet.parse("$header\nL1,UPI-MAHADEV MEDICOS-PAYMENT,,UPI,DEBIT,HEALTH,,real-shaped local\nL2,ZOMATO LTD,,CARD_CREDIT,DEBIT,FOOD,,real-shaped local")
        val scored = LearnedLabeller().runWithPrior(local, golden)
        assertEquals(listOf("L1", "L2"), scored.outcomes.map { it.case.id })
        assertEquals(listOf("L1"), scored.answers.map { it.caseId }, "only the row the rules left as Other is asked")
        val file = kotlin.io.path.createTempFile("golden", ".local.csv").toFile().apply { writeText("$header\nL1,UPI-MAHADEV MEDICOS-PAYMENT,,UPI,DEBIT,HEALTH,,local\n") }
        localReport(file) // prints only
        file.delete()
    }

    @Test
    fun `naive Bayes, the dependency-free alternative, is no better calibrated`() {
        val nb = rulesLearnedRun(NaiveBayesTrainer())
        val nbEce = nb.calibrationError ?: fail("nothing was held out")
        val ece = run.calibrationError ?: fail("nothing was held out")
        assertTrue(ece <= nbEce, "softmax $ece vs naive Bayes $nbEce")
    }
}
