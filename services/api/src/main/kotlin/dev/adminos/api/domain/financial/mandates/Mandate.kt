package dev.adminos.api.domain.financial.mandates

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** How the money is pulled (AOS-502). */
enum class MandateRail { UPI_AUTOPAY, NACH, CARD_SI, UNKNOWN }

enum class MandateFrequency(val periodic: Boolean) {
    DAILY(true), WEEKLY(true), FORTNIGHTLY(true), MONTHLY(true), QUARTERLY(true), HALF_YEARLY(true), YEARLY(true),
    /** Debited when the payee asks (e.g. a utility AutoPay): no schedule to predict. */
    AS_PRESENTED(false),
    ONE_TIME(false),
    UNKNOWN(false);

    /** The next debit after [from], or null when there's no schedule. */
    fun after(from: LocalDate): LocalDate? = when (this) {
        DAILY -> from.plusDays(1)
        WEEKLY -> from.plusWeeks(1)
        FORTNIGHTLY -> from.plusWeeks(2)
        MONTHLY -> from.plusMonths(1)
        QUARTERLY -> from.plusMonths(3)
        HALF_YEARLY -> from.plusMonths(6)
        YEARLY -> from.plusYears(1)
        AS_PRESENTED, ONE_TIME, UNKNOWN -> null
    }
}

enum class MandateStatus { ACTIVE, PAUSED, REVOKED, EXPIRED, COMPLETED }

/**
 * A standing permission for a payee to debit the user's account (UPI AutoPay, e-NACH, a card
 * standing instruction) — learnt from bank SMS, never from the text itself (D-33).
 */
data class Mandate(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val rail: MandateRail,
    val payee: String,
    /** UPI Unique Mandate Number or NACH UMRN, when the SMS shows one: the strongest identity. */
    val umn: String? = null,
    /** The mandate's amount, or its "up to" limit. */
    val amount: BigDecimal? = null,
    val frequency: MandateFrequency = MandateFrequency.UNKNOWN,
    val status: MandateStatus = MandateStatus.ACTIVE,
    /** When the latest status-changing message was received: older messages never undo it. */
    val statusChangedAt: Instant,
    val createdOn: LocalDate? = null,
    val validUntil: LocalDate? = null,
    val nextDebitOn: LocalDate? = null,
    /** The amount the bank's pre-debit notice announced for [nextDebitOn]. */
    val nextDebitAmount: BigDecimal? = null,
    val lastDebitAt: Instant? = null,
    val lastDebitAmount: BigDecimal? = null,
    val lastDebitTxnId: UUID? = null,
    val bank: String? = null,
    /** Fingerprint of the last SMS applied, so the same message twice changes nothing. */
    val lastMessageRef: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    init {
        require(payee.isNotBlank()) { "A mandate needs a payee" }
        require(amount == null || amount.signum() > 0) { "A mandate amount must be positive" }
    }
}

/** What one bank SMS says about a mandate (MandateSmsParser). */
enum class MandateNoticeKind { CREATED, PRE_DEBIT, PAUSED, RESUMED, REVOKED }

data class MandateNotice(
    val kind: MandateNoticeKind,
    val rail: MandateRail,
    val payee: String,
    val amount: BigDecimal? = null,
    val umn: String? = null,
    val frequency: MandateFrequency = MandateFrequency.UNKNOWN,
    /** Pre-debit: the day the money will go. */
    val debitOn: LocalDate? = null,
    val validUntil: LocalDate? = null,
    val bank: String? = null
)

interface MandateRepository {
    suspend fun save(mandate: Mandate): Mandate
    suspend fun update(mandate: Mandate): Mandate
    suspend fun findByUserId(userId: UUID): List<Mandate>
    suspend fun findByUmn(userId: UUID, umn: String): Mandate?
}

class InMemoryMandateRepository : MandateRepository {
    private val store = java.util.concurrent.ConcurrentHashMap<UUID, Mandate>()

    override suspend fun save(mandate: Mandate): Mandate {
        store[mandate.id] = mandate
        return mandate
    }

    override suspend fun update(mandate: Mandate): Mandate = save(mandate.copy(updatedAt = Instant.now()))

    override suspend fun findByUserId(userId: UUID): List<Mandate> = store.values.filter { it.userId == userId }.sortedBy { it.createdAt }

    override suspend fun findByUmn(userId: UUID, umn: String): Mandate? =
        store.values.firstOrNull { it.userId == userId && it.umn.equals(umn, ignoreCase = true) }
}
