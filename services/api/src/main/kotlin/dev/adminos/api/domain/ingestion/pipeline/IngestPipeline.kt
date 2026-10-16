package dev.adminos.api.domain.ingestion.pipeline

import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.ingestion.events.IngestEvent

/** State flowing through the steps: the event and the transaction being built from it. */
data class PipelineContext(val event: IngestEvent, val transaction: Transaction? = null) {
    /** The transaction under construction; steps after [ToTransactionStep] can rely on it. */
    val txn: Transaction get() = checkNotNull(transaction) { "ToTransactionStep must run first" }
}

sealed interface StepResult {
    data class Continue(val context: PipelineContext) : StepResult
    /** Stop processing: the event is valid but must not become a transaction (e.g. a duplicate). */
    data class Drop(val reason: String) : StepResult
}

/**
 * One stage of the accuracy engine (Chain of Responsibility, docs/ENGINEERING.md §6).
 * New behaviour (dedup, self-transfer, refunds, user overrides) is a new step, not an edit here.
 */
fun interface IngestStep {
    suspend fun apply(context: PipelineContext): StepResult
}

sealed interface PipelineOutcome {
    data class Created(val transaction: Transaction) : PipelineOutcome
    data class Ignored(val reason: String) : PipelineOutcome
}

/** Runs the steps in order. Pure orchestration: no I/O of its own. */
class IngestPipeline(private val steps: List<IngestStep>) {
    init {
        require(steps.firstOrNull() is ToTransactionStep) { "ToTransactionStep must be the first step" }
    }

    suspend fun run(event: IngestEvent): PipelineOutcome {
        var context = PipelineContext(event)
        for (step in steps) {
            when (val result = step.apply(context)) {
                is StepResult.Continue -> context = result.context
                is StepResult.Drop -> return PipelineOutcome.Ignored(result.reason)
            }
        }
        return PipelineOutcome.Created(context.txn)
    }
}
