package dev.adminos.api.domain.ingestion.pipeline

import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.ingestion.events.IngestEvent

/** A possible duplicate to show the user: [target] may be the same payment as the new transaction. */
data class MatchSuggestion(val target: Transaction, val score: Int, val reason: String)

/** State flowing through the steps: the event, the transaction being built, and any duplicate suggestion. */
data class PipelineContext(val event: IngestEvent, val transaction: Transaction? = null, val suggestion: MatchSuggestion? = null) {
    /** The transaction under construction; steps after [ToTransactionStep] can rely on it. */
    val txn: Transaction get() = checkNotNull(transaction) { "ToTransactionStep must run first" }
}

sealed interface StepResult {
    data class Continue(val context: PipelineContext) : StepResult
    /** Stop processing: the event is valid but must not become a transaction. */
    data class Drop(val reason: String) : StepResult
    /** Stop processing: the event is the same payment as [target]; link it and fill [target]'s blanks. */
    data class Merge(val target: Transaction, val score: Int, val reason: String) : StepResult
}

/**
 * One stage of the accuracy engine (Chain of Responsibility, docs/ENGINEERING.md §6).
 * New behaviour (dedup, self-transfer, refunds, user overrides) is a new step, not an edit here.
 */
fun interface IngestStep {
    suspend fun apply(context: PipelineContext): StepResult
}

sealed interface PipelineOutcome {
    data class Created(val transaction: Transaction, val suggestion: MatchSuggestion? = null) : PipelineOutcome
    data class Merged(val target: Transaction, val score: Int, val reason: String) : PipelineOutcome
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
                is StepResult.Merge -> return PipelineOutcome.Merged(enrich(result.target, context.txn), result.score, result.reason)
            }
        }
        return PipelineOutcome.Created(context.txn, context.suggestion)
    }
}

/**
 * Fills [target]'s blanks from another source's view of the same payment. Never overwrites a value
 * (user edits and the first source win), and never touches category or amount.
 */
internal fun enrich(target: Transaction, other: Transaction): Transaction = target.copy(
    merchantName = target.merchantName ?: other.merchantName,
    merchantRaw = target.merchantRaw ?: other.merchantRaw,
    accountLast4 = target.accountLast4 ?: other.accountLast4,
    accountId = target.accountId ?: other.accountId,
    paymentMethod = target.paymentMethod ?: other.paymentMethod,
    upiVpa = target.upiVpa ?: other.upiVpa,
    paymentRef = target.paymentRef ?: other.paymentRef
)
