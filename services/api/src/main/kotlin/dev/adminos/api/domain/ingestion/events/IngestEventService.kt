package dev.adminos.api.domain.ingestion.events

import java.time.Clock
import java.time.Duration

/** Validates and stores normalized events from every source adapter (D-21). */
class IngestEventService(
    private val repository: IngestEventRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    companion object {
        const val MAX_BATCH = 100
        /** Bank timestamps can be slightly ahead of our clock; anything further is a bad event. */
        val MAX_CLOCK_SKEW: Duration = Duration.ofMinutes(10)
    }

    suspend fun ingest(events: List<IngestEvent>): IngestResult {
        require(events.isNotEmpty()) { "events must not be empty" }
        require(events.size <= MAX_BATCH) { "at most $MAX_BATCH events per request" }
        val latestAllowed = clock.instant().plus(MAX_CLOCK_SKEW)
        events.forEachIndexed { i, e ->
            require(!e.occurredAt.isAfter(latestAllowed)) { "events[$i].occurredAt is in the future" }
        }
        // Duplicates inside the same batch count as duplicates too.
        val unique = events.distinctBy { Triple(it.userId, it.source, it.sourceRef) }
        val accepted = repository.saveNew(unique)
        return IngestResult(accepted = accepted, duplicates = events.size - accepted)
    }
}
