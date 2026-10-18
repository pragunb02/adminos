package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.RateLimiter
import dev.adminos.api.infrastructure.plugins.RateLimiters
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// ── Response DTOs (money is exact decimal strings, D-30) ──

@Serializable data class ForecastHistoryDto(val days: Long, val neededDays: Int, val readyOn: String?)
@Serializable data class ForecastRangeDto(val low: String, val high: String, val level: Int, val months: Int)
@Serializable data class DueDto(
    val name: String, val date: String, val amount: String, val overdue: Boolean,
    /** A projected bill's type (`electricity`, `mobile`…), how many months running it was seen, and since when (`YYYY-MM`). */
    val billType: String? = null, val monthsSeen: Int? = null, val seenFrom: String? = null
)

/**
 * One line of "How we got this". `bills`, `renewals`, `mandates` and `projected_bills` list their [items]; `everyday` gives its
 * daily rates, the number of each kind of day, and the days it learnt from.
 */
@Serializable data class ForecastPartDto(
    val kind: String,
    val amount: String,
    val items: List<DueDto>,
    val weekdayRate: String?,
    val weekendRate: String?,
    val weekdays: Int?,
    val weekendDays: Int?,
    val split: Boolean?,
    val method: String?,
    val learntFrom: String?,
    val learntTo: String?
)

@Serializable data class MonthEndDto(
    val month: String,
    val spentSoFar: String,
    val total: String,
    val daysLeft: Int,
    val parts: List<ForecastPartDto>,
    val range: ForecastRangeDto?,
    val rangeMonthsNeeded: Int
)

/** An account's balance in "money left" (AOS-720); [reason] says why one isn't counted (stale / before_salary). */
@Serializable data class ForecastBalanceDto(
    val bank: String?,
    val last4: String,
    val amount: String,
    val observedOn: String,
    val source: String,
    val reason: String? = null
)

@Serializable data class UntilSalaryDto(
    val from: String?,
    val to: String?,
    val spentSince: String,
    val expected: String,
    val left: String,
    val leftLow: String?,
    val leftHigh: String?,
    val parts: List<ForecastPartDto>,
    /** What "left" starts from: balance (recent bank balances, AOS-720) or salary (the last salary, D-76). */
    val basis: String = "salary",
    /** The first day [spentSince] counts; null when nothing is subtracted (a balance from today). */
    val spentFrom: String? = null,
    /** The counted balances' sum, and the oldest of their days (basis balance only). */
    val balance: String? = null,
    val balanceAsOf: String? = null,
    val accounts: List<ForecastBalanceDto> = emptyList(),
    val notCounted: List<ForecastBalanceDto> = emptyList(),
    val balanceMaxAgeDays: Long = MonthEndForecaster.MAX_BALANCE_AGE_DAYS
)

@Serializable data class SalaryDto(
    /** expected / late / unknown */
    val status: String,
    /** Why it's unknown: no_regular_credit / amount_varies / date_varies / stopped. */
    val reason: String?,
    val payer: String?,
    val expectedOn: String?,
    val typicalAmount: String?,
    val lastAmount: String?,
    val lastReceivedOn: String?,
    val credits: Int?,
    val untilSalary: UntilSalaryDto?
)

@Serializable data class ForecastResponse(
    /** ready / not_enough_history */
    val status: String,
    val today: String,
    val history: ForecastHistoryDto?,
    val monthEnd: MonthEndDto?,
    val salary: SalaryDto?
)

/** `GET /api/v1/forecast` — the month-end forecast and money left until salary, on the user's calendar (AOS-714). */
fun Route.forecastRoutes(service: ForecastService, limiter: RateLimiter = RateLimiters.forecast()) {
    authenticate("auth-jwt") {
        get("/forecast") {
            val principal = call.userPrincipal
            // It reads over a year of history per call: a per-user limit keeps a stolen session from burning CPU.
            val key = "forecast:${principal.userId}"
            if (!limiter.isAllowed(key)) {
                throw ApiException(HttpStatusCode.TooManyRequests, ApiError("RATE_001", "Too many forecasts. Try again in ${limiter.retryAfterSeconds(key)} seconds."))
            }
            val forecast = service.forecast(principal.userId, principal.zone)
            call.respond(HttpStatusCode.OK, ApiResponse.success(forecast.toResponse(), call.requestId))
        }
    }
}

internal fun Forecast.toResponse(): ForecastResponse = when (this) {
    is Forecast.NotEnoughHistory -> ForecastResponse("not_enough_history", today.toString(),
        history = ForecastHistoryDto(historyDays, neededDays, readyOn?.toString()), monthEnd = null, salary = null)
    is Forecast.Ready -> ForecastResponse("ready", monthEnd.today.toString(), history = null, monthEnd = monthEnd.toDto(), salary = salary.toDto())
}

private fun MonthEndForecast.toDto() = MonthEndDto(
    month = month.toString(), spentSoFar = spentSoFar.toMoney(), total = total.toMoney(),
    daysLeft = remaining?.days?.toInt() ?: 0, parts = parts.map { it.toDto() },
    range = range?.let { ForecastRangeDto(it.low.toMoney(), it.high.toMoney(), it.level, it.months) },
    rangeMonthsNeeded = rangeMonthsNeeded
)

private fun SalaryOutlook.toDto(): SalaryDto = when (this) {
    is SalaryOutlook.Unknown -> SalaryDto("unknown", reason = reason.name.lowercase(), payer = payer, expectedOn = null, typicalAmount = null,
        lastAmount = null, lastReceivedOn = lastSeen?.toString(), credits = null, untilSalary = null)
    is SalaryOutlook.Late -> salary.toDto("late", null)
    is SalaryOutlook.Expected -> untilSalary.salary.toDto("expected", untilSalary.toDto())
}

private fun DetectedSalary.toDto(status: String, until: UntilSalaryDto?) = SalaryDto(
    status = status, reason = null, payer = payer, expectedOn = expectedOn.toString(), typicalAmount = typicalAmount.toMoney(),
    lastAmount = lastAmount.toMoney(), lastReceivedOn = lastReceivedOn.toString(), credits = credits, untilSalary = until
)

private fun UntilSalary.toDto(): UntilSalaryDto {
    val balances = basis as? MoneyBasis.Balances
    return UntilSalaryDto(
        from = window?.from?.toString(), to = window?.to?.toString(), spentSince = spentSince.toMoney(), expected = expected.toMoney(),
        left = left.toMoney(), leftLow = leftRange?.first?.toMoney(), leftHigh = leftRange?.second?.toMoney(), parts = parts.map { it.toDto() },
        basis = if (balances != null) "balance" else "salary",
        spentFrom = spentFrom?.toString(),
        balance = balances?.start?.toMoney(), balanceAsOf = balances?.asOf?.toString(),
        accounts = balances?.accounts.orEmpty().map { it.toDto(null) },
        notCounted = notCounted.map { it.balance.toDto(it.reason.name.lowercase()) }
    )
}

private fun BankBalance.toDto(reason: String?) = ForecastBalanceDto(bankCode, last4, amount.toMoney(), observedOn.toString(), source, reason)

private fun ComponentEstimate.toDto(): ForecastPartDto = when (this) {
    is ComponentEstimate.Dues -> ForecastPartDto(
        kind = when (source) { DueSource.BILL -> "bills"; DueSource.RENEWAL -> "renewals"; DueSource.MANDATE -> "mandates" },
        amount = amount.toMoney(),
        items = items.map { DueDto(it.name, it.date.toString(), it.amount.toMoney(), it.overdue) },
        weekdayRate = null, weekendRate = null, weekdays = null, weekendDays = null, split = null, method = null, learntFrom = null, learntTo = null
    )
    is ProjectedBillsEstimate -> ForecastPartDto(
        kind = "projected_bills", amount = amount.toMoney(),
        items = items.map { DueDto(it.name, it.expectedOn.toString(), it.amount.toMoney(), overdue = false, billType = it.type.name.lowercase(), monthsSeen = it.monthsSeen, seenFrom = it.seenFrom.toString()) },
        weekdayRate = null, weekendRate = null, weekdays = null, weekendDays = null, split = null, method = null, learntFrom = null, learntTo = null
    )
    is ComponentEstimate.RunRate -> ForecastPartDto(
        kind = "everyday", amount = amount.toMoney(), items = emptyList(),
        weekdayRate = fit.rateOf(DayType.WEEKDAY).toMoney(), weekendRate = fit.rateOf(DayType.WEEKEND).toMoney(),
        weekdays = days.getValue(DayType.WEEKDAY), weekendDays = days.getValue(DayType.WEEKEND), split = fit.split,
        method = fit.estimator.name.lowercase(), learntFrom = fit.lookback.from.toString(), learntTo = fit.lookback.to.toString()
    )
}
