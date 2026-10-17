package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.TransactionCategory

/**
 * A category guess with its calibrated probability (AOS-707): [confidence] is the model's probability that
 * [category] is right, after calibration, so 0.8 means "right about 8 times in 10" on held-out data.
 * [cues] are the words of the payment that pushed most towards the category, strongest first.
 */
data class CategoryGuess(
    val category: TransactionCategory,
    val confidence: Double,
    val cues: List<String> = emptyList()
) {
    init {
        require(confidence in 0.0..1.0) { "A confidence is a probability, was $confidence" }
        require(category != TransactionCategory.TRANSFER) { "A model never guesses Transfer (D-41)" }
    }
}

/**
 * The port for a learned categoriser (AOS-707): anything that can turn a [CategoryInput] into a calibrated guess.
 * Implementations are swappable (a softmax regression today; a nearest-neighbour or embedding model later).
 * A model knows nothing about rules or users: [LearnedCategorizer] decides whether a guess may be used.
 */
interface CategoryModel {
    /** Names the algorithm and its format, e.g. `softmax-v1`; stored with the model. */
    val algorithm: String

    /** The categories the model can answer with (never Transfer). */
    val categories: List<TransactionCategory>

    /** The most likely category. Unseen inputs give a low confidence rather than an error. */
    fun predict(input: CategoryInput): CategoryGuess

    /** The probability of every category in [categories], summing to 1. */
    fun distribution(input: CategoryInput): Map<TransactionCategory, Double>
}

/** Where a training row came from: it decides the row's weight and whether it counts as one of the user's own. */
enum class ExampleOrigin(val weight: Double) {
    /** The user chose the category by hand (a correction, or a category picked at entry). */
    CORRECTION(3.0),
    /** A payee's remembered rule re-labelled it (D-41): the user's choice, applied to that payee. */
    REMEMBERED(3.0),
    /** The built-in rules decided a real category (never "left as Other"). */
    RULES(1.0),
    /** The synthetic golden set (D-61), so a model has a general prior before it knows the user. */
    PRIOR(1.0);

    val isUsers: Boolean get() = this == CORRECTION || this == REMEMBERED
}

/**
 * One labelled row to learn from. [key] identifies the payee, so a payee counts once.
 * [easyForRules] is true when the built-in rules already name a category for it: such rows teach the model, but
 * the model is only ever asked about the others, so calibration is fitted on those ([CategoryModelFitter]).
 */
data class TrainingExample(
    val key: String,
    val input: CategoryInput,
    val label: TransactionCategory,
    val origin: ExampleOrigin,
    val easyForRules: Boolean = origin == ExampleOrigin.RULES
) {
    init {
        require(key.isNotBlank()) { "A training example has a key" }
        require(label != TransactionCategory.TRANSFER) { "Transfers are never learned (D-41, D-39)" }
    }

    val weight: Double get() = origin.weight
}

/**
 * Trains a [CategoryModel] (AOS-707). Deterministic for the same examples and seed.
 * Calibration is separate ([CategoryModelFitter]), so every trainer gets it the same way.
 */
interface CategoryModelTrainer {
    val algorithm: String

    /**
     * Needs at least two categories among [examples]; a single category teaches nothing (callers check
     * this first and report it as a cold start).
     */
    fun train(examples: List<TrainingExample>): CategoryModel
}
