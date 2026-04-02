package dev.adminos.api.domain.ingestion.webhook

import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.ConnectionStatus
import dev.adminos.api.domain.ingestion.connection.SourceType
import dev.adminos.api.domain.ingestion.connection.UserConnection
import dev.adminos.api.domain.ingestion.sync.IngestionService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Daily fallback cron service for Gmail sync.
 * Uses bounded coroutine parallelism (max 10 concurrent) for scalability.
 */
class GmailCronService(
    private val connectionService: ConnectionService,
    private val ingestionService: IngestionService
) {
    private val logger = LoggerFactory.getLogger(GmailCronService::class.java)

    suspend fun runDailyFallbackSync(): DailySyncResult = coroutineScope {
        logger.info("Starting daily Gmail fallback sync")

        val connections = connectionService.findConnectedBySourceType(SourceType.GMAIL)
        val enqueued = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val errors = AtomicInteger(0)

        // Bounded concurrency — max 10 parallel enqueue operations
        val semaphore = Semaphore(10)

        val jobs = connections.map { connection ->
            async {
                semaphore.withPermit {
                    processConnection(connection, enqueued, skipped, errors)
                }
            }
        }

        jobs.awaitAll()

        val result = DailySyncResult(
            totalConnections = connections.size,
            enqueued = enqueued.get(),
            skipped = skipped.get(),
            errors = errors.get()
        )

        logger.info("Daily fallback sync complete: enqueued={}, skipped={}, errors={}",
            result.enqueued, result.skipped, result.errors)

        result
    }

    private suspend fun processConnection(
        connection: UserConnection,
        enqueued: AtomicInteger,
        skipped: AtomicInteger,
        errors: AtomicInteger
    ) {
        try {
            if (connection.tokenExpiresAt != null && connection.tokenExpiresAt.isBefore(Instant.now())) {
                logger.warn("Token expired for connection {}, marking as error", connection.id)
                handleTokenExpiry(connection)
                errors.incrementAndGet()
                return
            }

            ingestionService.enqueueGmailIngest(
                userId = connection.userId,
                connectionId = connection.id,
                historyId = connection.historyId
            )
            enqueued.incrementAndGet()
        } catch (e: Exception) {
            logger.error("Failed to enqueue fallback sync for connection {}: {}", connection.id, e.message)
            errors.incrementAndGet()
        }
    }

    private suspend fun handleTokenExpiry(connection: UserConnection) {
        try {
            connectionService.disconnect(connection.userId, connection.id)
        } catch (e: Exception) {
            logger.error("Failed to handle token expiry for connection {}: {}", connection.id, e.message)
        }
    }
}

data class DailySyncResult(val totalConnections: Int, val enqueued: Int, val skipped: Int, val errors: Int)
