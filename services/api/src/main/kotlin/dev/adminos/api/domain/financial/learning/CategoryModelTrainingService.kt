package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Why a user has no model yet. */
enum class ColdStartReason {
    /** Fewer corrected payees than [LearnedPolicy.minCorrections]. */
    TOO_FEW_CORRECTIONS,
    /** Everything learned from has the same category: nothing to tell apart. */
    ONE_CATEGORY
}

sealed interface TrainingOutcome {
    /** Learning from corrections is switched off for this deployment (`LEARNED_CATEGORIZATION_ENABLED`). */
    data object Disabled : TrainingOutcome

    /** No model: any stored one was removed, so the learned step does nothing for this user. */
    data class ColdStart(val reason: ColdStartReason, val corrections: Int, val needed: Int) : TrainingOutcome

    data class Trained(val model: StoredCategoryModel) : TrainingOutcome
}

/** What a retrain did: the training outcome, and what the pass over recent Other rows changed. */
data class RetrainReport(val outcome: TrainingOutcome, val backfill: Map<LearnedOutcome, Int> = emptyMap())

enum class ModelState { OFF, COLD_START, READY, CORRUPT }

/** A user's model, as their status page shows it. Counts and ratios only. */
data class ModelStatus(
    val state: ModelState,
    val corrections: Int,
    val correctionsNeeded: Int,
    val mode: ModelMode? = null,
    val trainedAt: Instant? = null,
    val summary: TrainingSummary? = null
)

/**
 * Trains, stores and reports on users' learned categorisers (AOS-707):
 * - **Cold start:** with fewer than [LearnedPolicy.minCorrections] corrected payees (or a single category) there is no
 *   model; a stored one is removed, so learning is off for that user.
 * - **Training:** the user's labelled payees from the last year plus the synthetic prior, fitted and calibrated by
 *   [CategoryModelFitter] off the request thread; one training per user at a time.
 * - **Mode:** [ModelMode.APPLY] only if, on held-out corrections it was sure of, the model was right at least
 *   [LearnedPolicy.minHeldOutPrecision] of the time over [LearnedPolicy.minHeldOutSure] or more; else [ModelMode.SUGGEST].
 * - **Retrain** also re-scores recent Other rows ([LearnedCategorizer.backfill]). Nothing leaves the machine.
 */
class CategoryModelTrainingService(
    private val transactions: TransactionRepository,
    private val store: CategoryModelStore,
    private val categorizer: LearnedCategorizer,
    private val prior: List<TrainingExample>,
    private val enabled: Boolean,
    private val policy: LearnedPolicy = LearnedPolicy(),
    private val fitter: CategoryModelFitter = CategoryModelFitter(SoftmaxRegressionTrainer()),
    private val clock: Clock = Clock.systemUTC()
) {
    init {
        require(prior.all { it.origin == ExampleOrigin.PRIOR }) { "The prior holds only prior rows" }
    }

    private val locks = ConcurrentHashMap<UUID, Mutex>()

    val isEnabled: Boolean get() = enabled

    suspend fun retrain(userId: UUID): RetrainReport {
        val outcome = train(userId)
        val backfill = if (outcome is TrainingOutcome.Trained) categorizer.backfill(userId) else emptyMap()
        return RetrainReport(outcome, backfill)
    }

    suspend fun train(userId: UUID): TrainingOutcome {
        if (!enabled) return TrainingOutcome.Disabled
        return locks.computeIfAbsent(userId) { Mutex() }.withLock {
            val set = trainingSet(userId)
            coldStart(set)?.let { cold ->
                store.remove(userId)
                return@withLock cold
            }
            val fitted = withContext(Dispatchers.Default) { fitter.fit(set.examples) }
            val stored = storedModel(userId, set, fitted)
            store.put(stored, fitted.model)
            TrainingOutcome.Trained(stored)
        }
    }

    suspend fun status(userId: UUID): ModelStatus {
        if (!enabled) return ModelStatus(ModelState.OFF, corrections = 0, correctionsNeeded = policy.minCorrections)
        val corrections = trainingSet(userId).corrections
        val needed = (policy.minCorrections - corrections).coerceAtLeast(0)
        return when (val loaded = store.load(userId)) {
            LoadedModel.None -> ModelStatus(ModelState.COLD_START, corrections, needed)
            is LoadedModel.Corrupt -> ModelStatus(ModelState.CORRUPT, corrections, needed)
            is LoadedModel.Ready -> store.stored(userId)?.let {
                ModelStatus(ModelState.READY, corrections, needed, it.mode, it.trainedAt, it.summary)
            } ?: ModelStatus(ModelState.COLD_START, corrections, needed)
        }
    }

    private suspend fun trainingSet(userId: UUID): TrainingSet {
        val since = clock.instant().minus(policy.trainingWindow)
        val rows = transactions.findByUserId(userId, from = since, limit = policy.maxTrainingRows)
        return TrainingSetBuilder.build(rows, prior)
    }

    private fun coldStart(set: TrainingSet): TrainingOutcome.ColdStart? = when {
        set.corrections < policy.minCorrections ->
            TrainingOutcome.ColdStart(ColdStartReason.TOO_FEW_CORRECTIONS, set.corrections, policy.minCorrections)
        set.categories.size < 2 -> TrainingOutcome.ColdStart(ColdStartReason.ONE_CATEGORY, set.corrections, policy.minCorrections)
        else -> null
    }

    private fun storedModel(userId: UUID, set: TrainingSet, fitted: FittedModel): StoredCategoryModel {
        val summary = summarize(set, fitted)
        val trusted = sure(fitted).size >= policy.minHeldOutSure && (summary.heldOutPrecision ?: 0.0) >= policy.minHeldOutPrecision
        val mode = if (trusted) ModelMode.APPLY else ModelMode.SUGGEST
        val encoded = CategoryModelCodec.encode(fitted.model)
        return StoredCategoryModel(userId, fitted.model.algorithm, encoded.formatVersion, encoded.bytes, encoded.checksum,
            mode, summary, clock.instant())
    }

    /** The user's held-out corrections the model named a category for at [LearnedPolicy.applyAt] or above. */
    private fun sure(fitted: FittedModel) = fitted.heldOut.filter {
        it.example.origin.isUsers && it.guess != TransactionCategory.OTHER && it.confidence >= policy.applyAt
    }

    private fun summarize(set: TrainingSet, fitted: FittedModel): TrainingSummary {
        val users = fitted.heldOut.filter { it.example.origin.isUsers }
        val sure = sure(fitted)
        val hard = fitted.heldOut.filterNot { it.example.easyForRules }
        return TrainingSummary(
            trainingRows = set.examples.size,
            corrections = set.corrections,
            categories = set.categories.size,
            heldOut = users.size,
            heldOutPrecision = sure.takeIf { it.isNotEmpty() }?.let { s -> Calibration.round4(s.count { it.correct }.toDouble() / s.size) },
            calibrationError = Calibration.expectedCalibrationError(hard.map { it.confidence to it.correct })?.let(Calibration::round4),
            temperature = fitted.model.temperature
        )
    }
}
