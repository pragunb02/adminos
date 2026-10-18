package dev.adminos.api.domain.balances

import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A balance an intake path saw (AOS-720). [accountHint] is the account's last 4 digits, or 3 when the bank masks
 * more (ICICI's "Acct XX123"); a 3-digit hint is only used when it names exactly one account already known.
 */
data class BalanceObservation(
    val userId: UUID,
    val bankCode: String?,
    val accountHint: String,
    val amount: BigDecimal,
    val observedAt: Instant,
    val source: BalanceSource,
    val sourceRef: String
) {
    override fun toString() = "BalanceObservation(${source.key}, observedAt=$observedAt)" // never the amount or the account
}

/** Why an observation wasn't recorded. Names only; never the figure. */
enum class BalanceRefusal {
    /** Observed more than [BalanceRules.FUTURE_SLACK] after now: a wrong clock or a forgery. */
    FUTURE,
    /** Not a believable balance: too many decimals, or beyond ±₹100 crore. */
    IMPLAUSIBLE,
    /** The hint isn't 3 or 4 digits. */
    NO_ACCOUNT,
    /** A 3-digit hint that names no known account, or more than one. */
    ACCOUNT_AMBIGUOUS
}

sealed interface BalanceRecordResult {
    data object Saved : BalanceRecordResult
    data object Duplicate : BalanceRecordResult
    data class Refused(val reason: BalanceRefusal) : BalanceRecordResult
}

/**
 * Keeps the latest known balance per account (AOS-720, D-170). Readings are append-only facts; "latest" is the
 * newest [BalanceReading.observedAt], never the newest to arrive, so a late or replayed message can't win.
 */
class BalanceService(private val repository: BalanceRepository, private val clock: Clock = Clock.systemUTC()) {
    private val log = LoggerFactory.getLogger(BalanceService::class.java)

    suspend fun record(o: BalanceObservation): BalanceRecordResult {
        val now = clock.instant()
        if (o.observedAt.isAfter(now.plus(BalanceRules.FUTURE_SLACK))) return refuse(o, BalanceRefusal.FUTURE)
        val amount = o.amount.takeIf { it.stripTrailingZeros().scale() <= 2 }?.setScale(2)
            ?.takeIf { it.abs() < BalanceReading.MAX_ABS } ?: return refuse(o, BalanceRefusal.IMPLAUSIBLE)
        val bank = BalanceAccount.bankCodeOf(o.bankCode)
        val account = when {
            FOUR.matches(o.accountHint) -> BalanceAccount(bank, o.accountHint)
            THREE.matches(o.accountHint) -> resolveMasked(o.userId, bank, o.accountHint) ?: return refuse(o, BalanceRefusal.ACCOUNT_AMBIGUOUS)
            else -> return refuse(o, BalanceRefusal.NO_ACCOUNT)
        }
        val saved = repository.save(BalanceReading(userId = o.userId, account = account, amount = amount, observedAt = o.observedAt,
            source = o.source, sourceRef = o.sourceRef, recordedAt = now))
        if (!saved) return BalanceRecordResult.Duplicate
        log.info("Balance noted for user {} from {}", o.userId, o.source.key) // never the amount or the account
        return BalanceRecordResult.Saved
    }

    /** Every account's latest balance on the user's calendar, labelled by age. */
    suspend fun overview(userId: UUID, zone: ZoneId): BalanceOverview {
        val today = LocalDate.now(clock.withZone(zone))
        val accounts = accountsOf(repository.forUser(userId)).map { (account, readings) ->
            val latest = readings.minWith(BalanceReading.NEWEST_FIRST)
            val on = latest.observedAt.atZone(zone).toLocalDate()
            AccountBalance(account, latest, on, ChronoUnit.DAYS.between(on, today).coerceAtLeast(0))
        }.sortedWith(compareBy<AccountBalance, String?>(nullsLast()) { it.account.bankCode }.thenBy { it.account.last4 })
        return BalanceOverview(today, accounts)
    }

    /**
     * Readings grouped per account: by the last 4 digits, and by bank only when the same digits come with two
     * different banks (then a reading that names no bank can't be placed and is left out, never guessed).
     */
    private fun accountsOf(readings: List<BalanceReading>): List<Pair<BalanceAccount, List<BalanceReading>>> =
        readings.groupBy { it.account.last4 }.flatMap { (last4, rs) ->
            val banks = rs.mapNotNull { it.account.bankCode }.toSortedSet()
            if (banks.size <= 1) listOf(BalanceAccount(banks.firstOrNull(), last4) to rs)
            else banks.map { b -> BalanceAccount(b, last4) to rs.filter { it.account.bankCode == b } }
        }

    private suspend fun resolveMasked(userId: UUID, bank: String?, last3: String): BalanceAccount? {
        val known = accountsOf(repository.forUser(userId)).map { it.first }
            .filter { it.last4.endsWith(last3) && (bank == null || it.bankCode == null || it.bankCode == bank) }
        return known.singleOrNull()?.let { BalanceAccount(it.bankCode ?: bank, it.last4) }
    }

    private fun refuse(o: BalanceObservation, reason: BalanceRefusal): BalanceRecordResult.Refused {
        log.info("Balance from {} not recorded for user {}: {}", o.source.key, o.userId, reason.name.lowercase())
        return BalanceRecordResult.Refused(reason)
    }

    private companion object {
        val FOUR = Regex("\\d{4}")
        val THREE = Regex("\\d{3}")
    }
}
