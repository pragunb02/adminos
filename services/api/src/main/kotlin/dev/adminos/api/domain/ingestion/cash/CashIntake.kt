package dev.adminos.api.domain.ingestion.cash

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.IngestSource
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** One cash payment as typed by the user. [clientRef] is generated once per entry on the device. */
data class CashEntry(
    val clientRef: String,
    val amount: BigDecimal,
    val category: TransactionCategory,
    val note: String? = null,
    val occurredAt: Instant? = null
)

/**
 * Cash quick-add (AOS-204): the manual-cash source adapter. Builds a normalized event for the common
 * intake (D-21), so cash goes through the same pipeline as every other source. The chosen category
 * is kept as user-set; the note becomes the merchant. Idempotent per [CashEntry.clientRef].
 */
class CashIntake(private val events: IngestEventService, private val clock: Clock = Clock.systemUTC()) {

    companion object {
        val MAX_AMOUNT = BigDecimal("10000000") // ₹1 crore: anything larger is a typo
        const val MAX_NOTE = 255
        private val CLIENT_REF = Regex("[A-Za-z0-9-]{8,64}")
    }

    /** @return true if saved, false if this entry was already saved (a double tap or retry). */
    suspend fun add(userId: UUID, entry: CashEntry): Boolean {
        require(CLIENT_REF.matches(entry.clientRef)) { "clientRef: 8–64 letters, digits or dashes" }
        require(entry.amount.signum() > 0) { "amount: must be more than 0" }
        require(entry.amount <= MAX_AMOUNT) { "amount: must be at most $MAX_AMOUNT" }
        require(entry.amount.scale() <= 2) { "amount: at most 2 decimal places" }
        val note = entry.note?.trim()?.takeIf { it.isNotEmpty() }
        require((note?.length ?: 0) <= MAX_NOTE) { "note: at most $MAX_NOTE characters" }
        val event = IngestEvent(
            userId = userId,
            source = IngestSource.MANUAL,
            sourceRef = "cash:${entry.clientRef}",
            occurredAt = entry.occurredAt ?: clock.instant(),
            direction = IngestDirection.DEBIT,
            amount = entry.amount,
            merchantRaw = note,
            paymentMethod = PaymentMethod.CASH,
            category = entry.category
        )
        return events.ingest(listOf(event)).accepted == 1
    }
}
