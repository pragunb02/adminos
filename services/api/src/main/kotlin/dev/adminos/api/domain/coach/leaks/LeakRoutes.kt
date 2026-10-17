package dev.adminos.api.domain.coach.leaks

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.lenses.LateNightDto
import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.Pagination
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionResponse
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.ZoneId
import java.util.UUID

// ── Response DTOs (money is an exact decimal string, D-30) ──

@Serializable data class LeakPeriodDto(val from: String, val to: String)
@Serializable data class LateNightNumbersDto(val orders: Int, val timedOrders: Int, val share: String?, val spent: String)
@Serializable data class WeekdayDto(val day: String, val orders: Int, val spent: String)
@Serializable data class LeakNumbersDto(
    val spent: String, val orders: Int, val averageOrder: String?, val lateNight: LateNightNumbersDto,
    val weekdays: List<WeekdayDto>, val previousSpent: String, val changePct: String?
)
@Serializable data class MerchantLeakDto(val merchant: String, val numbers: LeakNumbersDto)
@Serializable data class LensLeakDto(val id: String, val label: String, val description: String, val numbers: LeakNumbersDto, val merchants: List<MerchantLeakDto>)
@Serializable data class LeaksResponse(
    val period: LeakPeriodDto, val previousPeriod: LeakPeriodDto, val lateNight: LateNightDto,
    val total: LeakNumbersDto, val lenses: List<LensLeakDto>
)

internal fun DateRange.dto() = LeakPeriodDto(from.toString(), to.toString())

internal fun LeakNumbers.dto() = LeakNumbersDto(
    spent = spent.toMoney(), orders = orders, averageOrder = averageOrder?.toMoney(),
    lateNight = LateNightNumbersDto(lateNight.orders, lateNight.timedOrders, lateNight.share?.toPlainString(), lateNight.spent.toMoney()),
    weekdays = weekdays.map { WeekdayDto(it.day.dto(), it.orders, it.spent.toMoney()) },
    previousSpent = previousSpent.toMoney(), changePct = changePct?.toPlainString()
)

internal fun DayOfWeek.dto(): String = name.take(3).lowercase()

internal fun LeakReport.dto() = LeaksResponse(
    period = range.dto(), previousPeriod = previousRange.dto(),
    lateNight = LateNightDto(lateNight.from.toString(), lateNight.to.toString()),
    total = total.dto(),
    lenses = lenses.map { l -> LensLeakDto(l.lens.id.value, l.lens.label, l.lens.description, l.numbers.dto(), l.merchants.map { MerchantLeakDto(it.merchant, it.numbers.dto()) }) }
)

/**
 * "Where it leaks" (AOS-902):
 * - `GET /coach/leaks?from&to`: per lens and merchant; default this month so far;
 * - `GET /coach/leaks/transactions?from&to&lens&merchant&lateNight&weekday&category&nature&q&limit`: what a number is made of,
 *   in the same envelope as `/transactions` (one page; `pagination.total` is the full count).
 */
fun Route.leakRoutes(service: LeakService) {
    authenticate("auth-jwt") {
        route("/coach/leaks") {
            get {
                val (userId, zone, range) = call.coachRange(service::defaultRange, service::validate)
                call.respond(HttpStatusCode.OK, ApiResponse.success(service.report(userId, range, zone).dto(), call.requestId))
            }
            get("/transactions") {
                val (userId, zone, range) = call.coachRange(service::defaultRange, service::validate)
                val filter = call.leakFilter(service)
                val found = service.transactions(userId, range, zone, filter, call.limitParam(default = 200, max = LeakService.MAX_LIST))
                call.respond(HttpStatusCode.OK, ApiResponse.paginated(
                    found.items.map { TransactionResponse.from(it) },
                    Pagination(cursor = null, hasMore = false, total = found.total.toLong()),
                    call.requestId
                ))
            }
        }
    }
}

private fun ApplicationCall.leakFilter(service: LeakService): LeakFilter {
    val lens = parameters["lens"]?.let { raw ->
        if (raw == ALL_LENSES) null
        else LensId.parse(raw)?.takeIf { service.matcher.catalogue.byId(it) != null }
            ?: invalidParam("lens", raw, "one of $ALL_LENSES, " + service.matcher.lenses.joinToString { it.id.value })
    }
    val merchant = parameters["merchant"]?.let { raw -> raw.trim().takeIf { it.isNotEmpty() && it.length <= 255 } ?: invalidParam("merchant", raw.take(40), "1..255 characters") }
    val weekday = parameters["weekday"]?.let { raw ->
        DayOfWeek.entries.firstOrNull { it.dto() == raw.lowercase() } ?: invalidParam("weekday", raw.take(20), "one of mon, tue, wed, thu, fri, sat, sun")
    }
    val nature = enumParam<TransactionNature>("nature")
    if (nature != null && nature != TransactionNature.SPEND && nature != TransactionNature.REFUND) invalidParam("nature", nature.name.lowercase(), "spend or refund")
    val search = parameters["q"]?.takeIf { it.isNotBlank() }?.also { if (it.length > 255) invalidParam("q", it.take(40), "at most 255 characters") }
    return LeakFilter(
        lens = lens, merchant = merchant, lateNight = booleanParam("lateNight") ?: false, weekday = weekday,
        category = enumParam<TransactionCategory>("category"), nature = nature, search = search
    )
}

/** `lens=all` (or no lens) means every lens together. */
const val ALL_LENSES = "all"

internal data class CoachRequest(val userId: UUID, val zone: ZoneId, val range: DateRange)

/** `from`/`to` dates in the user's zone: both or neither (neither = [default]). Errors are 400s naming the parameter. */
internal fun ApplicationCall.coachRange(default: (ZoneId) -> DateRange, validate: (DateRange) -> Unit): CoachRequest {
    val zone = userZone
    val from = dateParam("from")
    val to = dateParam("to")
    val range = when {
        from == null && to == null -> default(zone)
        from != null && to != null -> DateRange(from, to)
        else -> throw IllegalArgumentException("${if (from == null) "from" else "to"}: give both from and to, or neither")
    }
    validate(range)
    return CoachRequest(userPrincipal.userId, zone, range)
}
