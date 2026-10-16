package dev.adminos.api.domain.ingestion.events

import dev.adminos.api.domain.financial.PaymentMethod
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Where an event came from. Each source has an adapter that produces [IngestEvent]s (D-21). */
enum class IngestSource { SMS, GMAIL, STATEMENT, WHATSAPP, MANUAL }

enum class IngestDirection { DEBIT, CREDIT }

enum class IngestEventStatus { PENDING, PROCESSING, PROCESSED, FAILED, IGNORED }

/**
 * One normalized money event from any source. Structured fields only: raw message text is
 * never accepted or stored here (D-33). `sourceRef` makes ingestion idempotent per (user, source).
 */
data class IngestEvent(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val source: IngestSource,
    val sourceRef: String,
    val occurredAt: Instant,
    val direction: IngestDirection,
    val amount: BigDecimal,
    val currency: String = "INR",
    val merchantRaw: String? = null,
    val accountHint: String? = null,
    val paymentMethod: PaymentMethod? = null,
    val upiVpa: String? = null,
    /** UPI/IMPS reference (RRN) when the source shows it; strongest cross-source match key (AOS-207). */
    val paymentRef: String? = null,
    val metadata: Map<String, Any?>? = null,
    val status: IngestEventStatus = IngestEventStatus.PENDING,
    val error: String? = null,
    val receivedAt: Instant = Instant.now(),
    val claimedAt: Instant? = null,
    val processedAt: Instant? = null
) {
    init {
        require(sourceRef.isNotBlank() && sourceRef.length <= 255) { "sourceRef must be 1..255 characters" }
        require(amount.signum() > 0) { "amount must be positive" }
        require(amount.scale() <= 2) { "amount must have at most 2 decimal places" }
        require(currency.matches(Regex("[A-Z]{3}"))) { "currency must be a 3-letter ISO code" }
        require(accountHint == null || accountHint.matches(Regex("\\d{4}"))) { "accountHint must be the last 4 digits" }
        require(merchantRaw == null || merchantRaw.length <= 255) { "merchantRaw must be at most 255 characters" }
        require(paymentRef == null || paymentRef.matches(Regex("[A-Za-z0-9]{6,32}"))) { "paymentRef must be 6..32 letters/digits" }
    }
}

/** Result of submitting a batch: new events stored vs. already-seen `sourceRef`s. */
data class IngestResult(val accepted: Int, val duplicates: Int)

interface IngestEventRepository {
    /** Stores events whose (user, source, sourceRef) is new; returns how many were inserted. */
    suspend fun saveNew(events: List<IngestEvent>): Int
    suspend fun findByUser(userId: UUID, limit: Int = 50): List<IngestEvent>
    /** Oldest pending events first, for the processing pipeline (outbox). */
    suspend fun findPending(limit: Int = 100): List<IngestEvent>

    /**
     * Atomically moves up to [limit] events to PROCESSING and returns them (oldest first): pending
     * ones, plus PROCESSING ones claimed before [staleBefore] (abandoned by a crashed processor).
     * Concurrent callers never receive the same event.
     */
    suspend fun claim(limit: Int, staleBefore: java.time.Instant): List<IngestEvent>

    /** Records the final state of a claimed event. */
    suspend fun finish(id: UUID, status: IngestEventStatus, error: String? = null)
}
