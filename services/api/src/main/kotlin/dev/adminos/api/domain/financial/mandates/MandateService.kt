package dev.adminos.api.domain.financial.mandates

import dev.adminos.api.domain.financial.BillTrackingService
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.financial.TransactionType
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** A mandate as the user should see it today: real status, the next debit to expect, and whether it looks unused. */
data class MandateView(val mandate: Mandate, val status: MandateStatus, val nextDebitOn: LocalDate?, val dormant: Boolean)

/**
 * Keeps the user's mandates (AOS-502) from bank SMS and their debits.
 *
 * **Which mandate a message is about:** the UMN/UMRN when shown; otherwise the same payee on the same
 * rail (an active one first).
 * **Out of order:** SMS history can arrive late (the Mac reader reads old messages), so a message
 * older than the mandate's last status change never changes its status, and a pre-debit date only
 * moves forward. The same message twice changes nothing.
 */
class MandateService(
    private val mandates: MandateRepository,
    private val transactions: TransactionRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(MandateService::class.java)

    /** Applies one SMS [notice] received at [receivedAt]; [messageRef] identifies the message. */
    suspend fun record(userId: UUID, notice: MandateNotice, receivedAt: Instant, messageRef: String): Mandate {
        val existing = find(userId, notice)
        if (existing != null && existing.lastMessageRef == messageRef) return existing
        val receivedOn = receivedAt.atZone(ZONE).toLocalDate()

        if (existing == null) {
            val created = Mandate(
                userId = userId, rail = notice.rail, payee = notice.payee, umn = notice.umn, amount = notice.amount,
                frequency = notice.frequency, status = statusFor(notice.kind) ?: MandateStatus.ACTIVE, statusChangedAt = receivedAt,
                createdOn = if (notice.kind == MandateNoticeKind.CREATED) receivedOn else null, validUntil = notice.validUntil,
                nextDebitOn = notice.debitOn, nextDebitAmount = notice.debitOn?.let { notice.amount }, bank = notice.bank,
                lastMessageRef = messageRef, createdAt = clock.instant(), updatedAt = clock.instant()
            )
            logger.info("Mandate {} recorded ({} on {}, {})", created.id, notice.kind, notice.rail, created.status)
            return mandates.save(created)
        }

        val newer = !receivedAt.isBefore(existing.statusChangedAt)
        val status = statusFor(notice.kind)
        val announcesLaterDebit = notice.debitOn != null && (existing.nextDebitOn == null || !notice.debitOn.isBefore(existing.nextDebitOn))
        val updated = existing.copy(
            umn = existing.umn ?: notice.umn,
            // The mandate's own amount comes from set-up/modify messages; a pre-debit notice is just this debit's amount.
            amount = when {
                notice.amount == null || notice.kind != MandateNoticeKind.CREATED -> existing.amount
                existing.amount == null || newer -> notice.amount
                else -> existing.amount
            },
            frequency = when {
                notice.frequency == MandateFrequency.UNKNOWN -> existing.frequency
                existing.frequency == MandateFrequency.UNKNOWN || newer -> notice.frequency
                else -> existing.frequency
            },
            status = if (newer && status != null) status else existing.status,
            statusChangedAt = if (newer && status != null) receivedAt else existing.statusChangedAt,
            createdOn = existing.createdOn ?: receivedOn.takeIf { notice.kind == MandateNoticeKind.CREATED },
            validUntil = notice.validUntil ?: existing.validUntil,
            nextDebitOn = if (announcesLaterDebit) notice.debitOn else existing.nextDebitOn,
            nextDebitAmount = if (announcesLaterDebit) notice.amount else existing.nextDebitAmount,
            bank = existing.bank ?: notice.bank,
            lastMessageRef = messageRef
        )
        return mandates.update(updated)
    }

    /**
     * After a debit is stored (listener): if it pays one of the user's mandates, record it, move the
     * next debit on, and mark the transaction recurring. @return the mandate it paid, or null
     */
    suspend fun recordDebit(t: Transaction): Mandate? {
        if (t.type != TransactionType.DEBIT || t.nature == TransactionNature.REFUND || t.nature == TransactionNature.INCOME) return null
        val candidates = mandates.findByUserId(t.userId).filter { it.status == MandateStatus.ACTIVE || it.status == MandateStatus.PAUSED }
        if (candidates.any { it.lastDebitTxnId == t.id }) return null
        val mandate = MandateDebitMatcher.mandateFor(t, candidates) ?: return null

        val debitDate = t.transactedAt.atZone(ZONE).toLocalDate()
        val updated = mandates.update(mandate.copy(
            lastDebitAt = t.transactedAt, lastDebitAmount = t.amount, lastDebitTxnId = t.id,
            nextDebitOn = mandate.frequency.after(debitDate) ?: mandate.nextDebitOn?.takeIf { it.isAfter(debitDate) },
            nextDebitAmount = null,
            status = if (mandate.frequency == MandateFrequency.ONE_TIME) MandateStatus.COMPLETED else mandate.status
        ))
        transactions.update(t.copy(isRecurring = true, recurringGroupId = mandate.id))
        logger.info("Transaction {} paid mandate {}", t.id, mandate.id)
        return updated
    }

    /** The user's mandates as of [today]: active first, then by next debit. */
    suspend fun list(userId: UUID, today: LocalDate): List<MandateView> =
        mandates.findByUserId(userId).map { view(it, today) }
            .sortedWith(compareBy<MandateView> { it.status != MandateStatus.ACTIVE }.thenBy { it.nextDebitOn ?: LocalDate.MAX }.thenBy { it.mandate.payee.lowercase() })

    internal fun view(m: Mandate, today: LocalDate): MandateView {
        val status = if (m.status == MandateStatus.ACTIVE && m.validUntil != null && m.validUntil.isBefore(today)) MandateStatus.EXPIRED else m.status
        val next = if (status == MandateStatus.ACTIVE) projectNext(m, today) else null
        return MandateView(m, status, next, dormant = status == MandateStatus.ACTIVE && dormant(m, today))
    }

    /** The next debit on or after [today]: the announced one, or the schedule rolled forward. */
    internal fun projectNext(m: Mandate, today: LocalDate): LocalDate? {
        var next = m.nextDebitOn ?: return null
        var guard = 0
        while (next.isBefore(today) && guard++ < 400) next = m.frequency.after(next) ?: return null
        return next
    }

    /** Active and periodic, but nothing taken for two cycles (plus a week): maybe forgotten. */
    internal fun dormant(m: Mandate, today: LocalDate): Boolean {
        if (!m.frequency.periodic) return false
        val since = m.lastDebitAt?.atZone(ZONE)?.toLocalDate() ?: m.createdOn ?: return false
        val twoCycles = m.frequency.after(m.frequency.after(since) ?: return false) ?: return false
        return twoCycles.plusDays(7).isBefore(today)
    }

    private suspend fun find(userId: UUID, notice: MandateNotice): Mandate? {
        notice.umn?.let { umn -> mandates.findByUmn(userId, umn)?.let { return it } }
        val samePayee = mandates.findByUserId(userId).filter {
            it.rail == notice.rail && normalize(it.payee) == normalize(notice.payee) && (notice.umn == null || it.umn == null)
        }
        return samePayee.firstOrNull { it.status == MandateStatus.ACTIVE } ?: samePayee.maxByOrNull { it.statusChangedAt }
    }

    private fun statusFor(kind: MandateNoticeKind): MandateStatus? = when (kind) {
        MandateNoticeKind.CREATED, MandateNoticeKind.RESUMED -> MandateStatus.ACTIVE
        MandateNoticeKind.PAUSED -> MandateStatus.PAUSED
        MandateNoticeKind.REVOKED -> MandateStatus.REVOKED
        MandateNoticeKind.PRE_DEBIT -> null // says nothing new about the status
    }

    companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

        /** "BAJAJ FINANCE LTD." ≈ "Bajaj Finance": letters and digits only, without company suffixes. */
        fun normalize(name: String): String = name.lowercase()
            .replace(Regex("\\b(pvt|private|ltd|limited|llp|india|inc|co)\\b\\.?"), " ")
            .replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}

/**
 * Which mandate a debit pays (AOS-502). Evidence, strongest first: the UMN in the debit SMS; the
 * payee's name (similar) with an amount within the mandate's (5% slack) **and** either the SMS
 * saying it's a mandate debit or the date within 3 days of the expected one. A tie matches nothing.
 */
object MandateDebitMatcher {
    fun mandateFor(t: Transaction, mandates: List<Mandate>): Mandate? {
        val umn = t.metadata?.get("umn") as? String
        if (umn != null) mandates.firstOrNull { it.umn.equals(umn, ignoreCase = true) }?.let { return it }
        val saysMandate = t.metadata?.get("mandate") == "true"
        val names = listOfNotNull(t.merchantName, t.merchantRaw).filter { it.isNotBlank() }
        val debitOn = t.transactedAt.atZone(MandateService.ZONE).toLocalDate()
        val matches = mandates.filter { m ->
            val nameMatches = names.any { n ->
                val a = MandateService.normalize(m.payee); val b = MandateService.normalize(n)
                a.isNotEmpty() && b.isNotEmpty() && (a == b || a in b || b in a || BillTrackingService.fuzzyMatch(a, b) > 0.8)
            }
            val limit = listOfNotNull(m.amount, m.nextDebitAmount).maxOrNull()
            val amountFits = limit == null || t.amount <= limit.multiply(BigDecimal("1.05"))
            val whenExpected = m.nextDebitOn?.let { java.time.temporal.ChronoUnit.DAYS.between(it, debitOn) in -3..3 } ?: false
            nameMatches && amountFits && (saysMandate || whenExpected)
        }
        return matches.singleOrNull()
    }
}
