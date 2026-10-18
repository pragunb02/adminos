package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.balances.BalanceService
import dev.adminos.api.domain.financial.BillType
import dev.adminos.api.domain.financial.DueItem
import dev.adminos.api.domain.financial.DueKind
import dev.adminos.api.domain.financial.DuesService
import dev.adminos.api.domain.financial.SubscriptionRepository
import dev.adminos.api.domain.financial.SubscriptionStatus
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * Loads what [MonthEndForecaster] needs for one user (AOS-714): their spending history, known dues
 * this month and next (the dues calendar, D-55/D-56), income credits and each account's latest balance (AOS-720),
 * all on their own calendar.
 */
class ForecastService(
    private val history: ForecastHistoryRepository,
    private val dues: DuesService,
    private val subscriptions: SubscriptionRepository,
    private val balances: BalanceService,
    private val forecaster: MonthEndForecaster = MonthEndForecaster(),
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun forecast(userId: UUID, zone: ZoneId): Forecast {
        val today = LocalDate.now(clock.withZone(zone))
        val first = history.firstActivityDay(userId, zone)
        if (first == null || first.isAfter(today)) {
            return Forecast.NotEnoughHistory(today, historyDays = 0, neededDays = MonthEndForecaster.MIN_HISTORY_DAYS, readyOn = null)
        }
        val from = maxOf(first, today.minusMonths(HISTORY_MONTHS).withDayOfMonth(1))
        val input = ForecastInput(
            today = today,
            history = spendHistory(userId, DateRange(from, today), zone, first),
            dues = knownDues(userId, today),
            incomes = history.incomes(userId, DateRange(maxOf(first, today.minusDays(INCOME_DAYS)), today), zone),
            balances = balances.overview(userId, zone).accounts.map {
                BankBalance(it.account.bankCode, it.account.last4, it.reading.amount, it.observedOn, it.reading.source.key)
            }
        )
        return forecaster.forecast(input)
    }

    /** Spending by day; what an active subscription, a mandate or a bill took is recurring (it's forecast as a due instead). */
    private suspend fun spendHistory(userId: UUID, range: DateRange, zone: ZoneId, first: LocalDate): SpendHistory {
        val subscribed = subscriptions.findByUserId(userId, limit = MAX_SUBSCRIPTIONS)
            .filter { it.status == SubscriptionStatus.ACTIVE || it.status == SubscriptionStatus.TRIAL }
            .flatMap { listOfNotNull(it.merchantName, it.name) }
            .map(::merchantKey).filter { it.isNotEmpty() }.toSet()
        val days = history.spending(userId, range, zone).map { row ->
            val recurring = row.recurring || merchantKey(row.merchant.orEmpty()) in subscribed
            if (recurring) SpendDay(row.day, java.math.BigDecimal.ZERO, row.amount) else SpendDay(row.day, row.amount, java.math.BigDecimal.ZERO)
        }
        return SpendHistory(days, first)
    }

    private suspend fun knownDues(userId: UUID, today: LocalDate): List<KnownDue> {
        val month = YearMonth.from(today)
        val thisMonth = dues.month(userId, month, today)
        val nextMonth = dues.month(userId, month.plusMonths(1), today)
        return (thisMonth.items + thisMonth.overdue + nextMonth.items).mapNotNull { it.toKnownDue(today) }
    }

    companion object {
        /** Twelve months of past errors, plus the run-rate's lookback before the oldest. */
        const val HISTORY_MONTHS = 14L
        /** Enough credits to see a monthly salary three times, and to notice it stopped. */
        const val INCOME_DAYS = 200L
        private const val MAX_SUBSCRIPTIONS = 500

        fun merchantKey(name: String): String = name.trim().lowercase()

        /**
         * A dues-calendar item as a known payment after [today], or null when it isn't one:
         * - a paid or cancelled bill is already in the history or won't happen;
         * - a credit-card bill is paid by a transfer (D-39), and its purchases are already counted as spending;
         * - an unpaid bill due today or earlier is expected tomorrow (marked overdue if it's past its date);
         * - renewals and mandate debits count from tomorrow (today's charge is already in "spent so far").
         */
        fun DueItem.toKnownDue(today: LocalDate): KnownDue? = when (kind) {
            DueKind.BILL -> {
                val open = status in OPEN_BILL && detail != BillType.CREDIT_CARD.name.lowercase() && amount.signum() > 0
                if (!open) null else KnownDue(DueSource.BILL, label(), maxOf(date, today.plusDays(1)), amount, overdue = date.isBefore(today))
            }
            DueKind.RENEWAL -> takeIf { date.isAfter(today) && amount.signum() > 0 }?.let { KnownDue(DueSource.RENEWAL, label(), date, amount) }
            DueKind.MANDATE -> takeIf { date.isAfter(today) && amount.signum() > 0 }?.let { KnownDue(DueSource.MANDATE, label(), date, amount) }
        }

        private val OPEN_BILL = setOf("upcoming", "due", "overdue")

        private fun DueItem.label(): String = name.trim().ifEmpty { kind.name.lowercase().replaceFirstChar { it.uppercase() } }
    }
}
