package dev.adminos.api.domain.ingestion.pipeline

import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestEventRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Persistence of a pipeline outcome. Implementations write the transaction and the event status atomically. */
interface IngestOutcomeWriter {
    /**
     * Inserts [transaction] (if none exists yet for the event), records [suggestion] if any, marks the event processed.
     * @return the stored transaction: [transaction], or the one an earlier attempt already stored
     */
    suspend fun processed(event: IngestEvent, transaction: Transaction, suggestion: MatchSuggestion? = null, relations: List<Relation> = emptyList()): Transaction
    /** Links the event to [target] (already enriched), saves [target], marks the event processed. */
    suspend fun merged(event: IngestEvent, target: Transaction, score: Int, reason: String)
    suspend fun ignored(event: IngestEvent, reason: String)
    suspend fun failed(event: IngestEvent, error: String)
}

/**
 * Turns pending intake events into transactions (D-34). Runs after each ingest ([wake]) and on a
 * periodic sweep, so nothing waits long or gets stuck. Claiming is safe across API instances.
 */
class IngestProcessor(
    private val events: IngestEventRepository,
    private val pipeline: IngestPipeline,
    private val writer: IngestOutcomeWriter,
    private val clock: Clock = Clock.systemUTC(),
    private val batchSize: Int = 100,
    /** Claims older than this are assumed abandoned (crashed processor) and retried. */
    private val claimTimeout: Duration = Duration.ofMinutes(5),
    /** Told about each stored transaction, after the commit (AOS-505). */
    private val listeners: List<TransactionCreatedListener> = emptyList()
) {
    private val logger = LoggerFactory.getLogger(IngestProcessor::class.java)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /** Ask for a prompt run (called after events are accepted). Never blocks. */
    fun wake() {
        wakeups.trySend(Unit)
    }

    /** Claims and processes one batch. @return number of events handled */
    suspend fun processBatch(): Int {
        val claimed = events.claim(batchSize, staleBefore = clock.instant().minus(claimTimeout))
        for (event in claimed) {
            try {
                when (val outcome = pipeline.run(event)) {
                    is PipelineOutcome.Created -> notify(writer.processed(event, outcome.transaction, outcome.suggestion, outcome.relations))
                    is PipelineOutcome.Merged -> writer.merged(event, outcome.target, outcome.score, outcome.reason)
                    is PipelineOutcome.Ignored -> writer.ignored(event, outcome.reason)
                }
            } catch (e: Exception) {
                // Events carry no raw message text (D-33), so the error is safe to store.
                logger.warn("Ingest event {} failed: {}", event.id, e.toString())
                writer.failed(event, "${e::class.simpleName}: ${e.message?.take(300)}")
            }
        }
        return claimed.size
    }

    /** Each listener on its own: one failing never affects the event or the other listeners. */
    private suspend fun notify(stored: Transaction) {
        for (listener in listeners) {
            try {
                listener.onCreated(stored)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Listener {} failed for transaction {}", listener::class.simpleName, stored.id, e)
            }
        }
    }

    /** Processes until no work is left, then waits for a [wake] or the sweep interval. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope, sweepEvery: kotlin.time.Duration = 5.seconds): Job = scope.launch {
        logger.info("Ingest processor started")
        while (isActive) {
            try {
                while (processBatch() == batchSize) { /* drain backlog */ }
            } catch (e: Exception) {
                logger.error("Ingest processor batch failed; retrying after the sweep interval", e)
            }
            select<Unit> {
                wakeups.onReceive { }
                onTimeout(sweepEvery) { }
            }
        }
    }
}
