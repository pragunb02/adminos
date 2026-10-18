package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.balances.BalanceRules
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * What "money left until salary" starts from (AOS-720, D-170). Kept apart from [MonthEndForecaster] so the forecast's
 * spending components and this choice of starting point change independently.
 */

/** An account's latest balance as the forecast sees it (AOS-720, D-170). Negative = overdrawn. */
data class BankBalance(val bankCode: String?, val last4: String, val amount: BigDecimal, val observedOn: LocalDate, val source: String) {
    override fun toString() = "BankBalance(${bankCode ?: "?"}, ••$last4, $observedOn)" // never the amount
}

/** Why an account's balance isn't in "money left". */
enum class NotCountedReason {
    /** Older than the forecast's limit ([MoneyLeftBasisPicker.maxBalanceAgeDays]). */
    STALE,
    /** From before the last salary came, so it doesn't hold it. */
    BEFORE_SALARY
}

data class NotCounted(val balance: BankBalance, val reason: NotCountedReason)

/** What "money left" starts from (AOS-720, D-170). */
sealed interface MoneyBasis {
    val start: BigDecimal
    /** The days whose spending is subtracted (through today); null when there are none. */
    fun spentSince(today: LocalDate): DateRange?

    /** The last salary, less everything spent since the day it came (D-76; when no recent balance is known). */
    data class LastSalary(val amount: BigDecimal, val receivedOn: LocalDate) : MoneyBasis {
        override val start: BigDecimal get() = amount
        override fun spentSince(today: LocalDate) = DateRange(receivedOn, today)
    }

    /**
     * The recent balances of the accounts in [accounts], less what was spent from the oldest of their days, [asOf],
     * through today. That day is counted in full: spending after a reading on its own day is never missed (the
     * history is by day), at the cost of counting what came before the reading twice. Conservative on purpose.
     */
    data class Balances(val accounts: List<BankBalance>, val asOf: LocalDate) : MoneyBasis {
        init {
            require(accounts.isNotEmpty()) { "A balance basis has at least one account" }
        }

        override val start: BigDecimal get() = accounts.fold(BigDecimal.ZERO) { acc, b -> acc + b.amount }.money()
        override fun spentSince(today: LocalDate) = asOf.takeIf { !it.isAfter(today) }?.let { DateRange(it, today) }
    }
}

/** The chosen [basis], and the known balances left out of it with why. */
data class PickedBasis(val basis: MoneyBasis, val notCounted: List<NotCounted>)

/**
 * Chooses what "money left" starts from (AOS-720, D-170). A balance counts when it's at most [maxBalanceAgeDays] old
 * (the balances' own stale limit, so the console never shows a number it calls stale elsewhere) and from the last
 * salary's day or later (an older one doesn't hold the salary). With none counted, the last salary is the start (D-76).
 * A balance dated after today (a clock or timezone skew) counts as today's, so nothing is subtracted twice. Stateless.
 */
class MoneyLeftBasisPicker(val maxBalanceAgeDays: Long = MAX_BALANCE_AGE_DAYS) {
    init {
        require(maxBalanceAgeDays >= 0) { "maxBalanceAgeDays: not negative" }
    }

    fun pick(today: LocalDate, salary: DetectedSalary, balances: List<BankBalance>): PickedBasis {
        val notCounted = mutableListOf<NotCounted>()
        val counted = balances.mapNotNull { b ->
            val reason = when {
                b.observedOn.isAfter(today) -> null
                ChronoUnit.DAYS.between(b.observedOn, today) > maxBalanceAgeDays -> NotCountedReason.STALE
                b.observedOn.isBefore(salary.lastReceivedOn) -> NotCountedReason.BEFORE_SALARY
                else -> null
            }
            if (reason != null) notCounted += NotCounted(b, reason)
            if (reason == null) b.copy(observedOn = minOf(b.observedOn, today)) else null
        }
        val basis = if (counted.isEmpty()) MoneyBasis.LastSalary(salary.lastAmount, salary.lastReceivedOn)
        else MoneyBasis.Balances(counted, counted.minOf { it.observedOn })
        return PickedBasis(basis, notCounted.toList())
    }

    companion object {
        /** A balance older than this isn't "money left" (the balances' stale limit, D-170). */
        const val MAX_BALANCE_AGE_DAYS = BalanceRules.MAX_AGE_DAYS
    }
}
