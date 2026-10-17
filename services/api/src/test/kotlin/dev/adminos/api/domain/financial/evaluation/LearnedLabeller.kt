package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.financial.learning.CategoryModelFitter
import dev.adminos.api.domain.financial.learning.CategoryModelTrainer
import dev.adminos.api.domain.financial.learning.CategoryPrior
import dev.adminos.api.domain.financial.learning.Calibration
import dev.adminos.api.domain.financial.learning.LearnedCategorizer
import dev.adminos.api.domain.financial.learning.LearnedOutcome
import dev.adminos.api.domain.financial.learning.LearnedPolicy
import dev.adminos.api.domain.financial.learning.LoadedModel
import dev.adminos.api.domain.financial.learning.ModelMode
import dev.adminos.api.domain.financial.learning.SoftmaxRegressionTrainer
import dev.adminos.api.domain.financial.learning.TrainingExample
import dev.adminos.api.domain.ingestion.pipeline.MerchantNormalizer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A golden row the learned step was asked about, with its calibrated answer (for the calibration error). */
data class LearnedAnswer(val caseId: String, val confidence: Double, val correct: Boolean, val outcome: LearnedOutcome)

/** Outcomes for the golden set plus the learned step's answers on the rows it was asked about. */
data class LearnedRun(val outcomes: List<Outcome>, val answers: List<LearnedAnswer>) {
    /** Held-out expected calibration error of the learned guesses (D-67 asks for ≤ 0.05). */
    val calibrationError: Double? get() = Calibration.expectedCalibrationError(answers.map { it.confidence to it.correct })
}

/**
 * Labels golden cases with the rules **and then** the learned categoriser (AOS-707), honestly: k-fold
 * cross-validation, so every row is labelled by a model that never saw it. For each fold, a model is fitted
 * (with its own calibration) on the other folds' rows as the synthetic prior, exactly as production fits the
 * prior plus a user's corrections; then the shipped [LearnedCategorizer.decide] runs on rows the rules left as Other.
 * [extraTraining] adds rows to every fold's training (the learning-curve test simulates corrections with it).
 */
class LearnedLabeller(
    private val trainer: CategoryModelTrainer = SoftmaxRegressionTrainer(),
    private val policy: LearnedPolicy = LearnedPolicy(),
    private val folds: Int = FOLDS,
    private val extraTraining: List<TrainingExample> = emptyList()
) {
    private val pipeline = PipelineLabeller()
    private val clock = Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC)

    suspend fun run(cases: List<LabelledCase>): LearnedRun {
        val prior = CategoryPrior.examples(cases, MerchantNormalizer()::normalize)
        val answers = mutableListOf<LearnedAnswer>()
        val byId = mutableMapOf<String, Outcome>()
        for ((fold, held) in cases.groupBy { foldOf(it) }.toSortedMap()) {
            val training = prior.filter { foldOfKey(it.key) != fold } + extraTraining
            val fitted = CategoryModelFitter(trainer).fit(training)
            val loaded = LoadedModel.Ready(fitted.model, ModelMode.APPLY, "${trainer.algorithm}@fold$fold")
            for (case in held) {
                val t = pipeline.transactionFor(case)
                if (!LearnedCategorizer.isRuleLeftOther(t)) {
                    byId[case.id] = Outcome(case, Prediction(Label(t.category, t.subcategory), t.categorySource))
                    continue
                }
                val decision = LearnedCategorizer.decide(t, loaded, policy, clock)
                val guess = fitted.model.predict(dev.adminos.api.domain.financial.learning.CategoryInput.of(t))
                answers += LearnedAnswer(case.id, guess.confidence, guess.category == case.expected.category, decision.outcome)
                val stored = decision.transaction
                byId[case.id] = Outcome(case, Prediction(Label(stored.category, stored.subcategory), stored.categorySource))
            }
        }
        return LearnedRun(cases.map { byId.getValue(it.id) }, answers)
    }

    private fun foldOf(case: LabelledCase) = foldOfKey(CategoryPrior.KEY_PREFIX + case.id)
    private fun foldOfKey(key: String) = Math.floorMod(key.hashCode(), folds)

    companion object {
        const val FOLDS = 5
    }
}
