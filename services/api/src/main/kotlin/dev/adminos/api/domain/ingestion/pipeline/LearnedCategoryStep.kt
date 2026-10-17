package dev.adminos.api.domain.ingestion.pipeline

import dev.adminos.api.domain.financial.learning.LearnedCategorizer

/**
 * The learned categoriser (AOS-707) as the last pipeline step: after the rules, remembered corrections, transfers
 * and refund links have all had their say, a row still left as plain Other may get the user's model's category
 * (when it is sure) or a suggestion. Local and fast: no network call during ingestion. Registered only when enabled.
 */
class LearnedCategoryStep(private val learned: LearnedCategorizer) : IngestStep {
    override suspend fun apply(context: PipelineContext): StepResult =
        StepResult.Continue(context.copy(transaction = learned.categorize(context.txn).transaction))
}
