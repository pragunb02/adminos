package dev.adminos.api.domain.analytics

import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

// ── Response DTOs (amounts are exact decimal strings, D-30) ──

@Serializable data class PeriodDto(val from: String, val to: String)
@Serializable data class MoneyDto(val spent: String, val received: String, val net: String)
@Serializable data class SummaryResponse(
    val period: PeriodDto, val totals: MoneyDto, val spendCount: Int,
    val previousPeriod: PeriodDto, val previous: MoneyDto, val spentChangePct: String?
)
@Serializable data class SeriesPointDto(val start: String, val spent: String, val received: String)
@Serializable data class SeriesResponse(val period: PeriodDto, val bucket: String, val points: List<SeriesPointDto>)
@Serializable data class CategoryDto(val category: String, val spent: String, val share: Double, val count: Int, val previousSpent: String)
@Serializable data class CategoriesResponse(val period: PeriodDto, val categories: List<CategoryDto>)
@Serializable data class MerchantDto(val merchant: String, val spent: String, val count: Int, val category: String)
@Serializable data class MerchantsResponse(val period: PeriodDto, val merchants: List<MerchantDto>)

private fun BigDecimal.money() = toMoney()
private fun Money.dto() = MoneyDto(spent.money(), received.money(), net.money())
private fun DateRange.dto() = PeriodDto(from.toString(), to.toString())

/**
 * GET /api/v1/analytics/{summary,series,categories,merchants} (AOS-301).
 * `from`/`to` are dates in the user's timezone (AOS-120); the default is this month so far.
 */
fun Route.analyticsRoutes(service: AnalyticsService) {
    authenticate("auth-jwt") {
        route("/analytics") {
            get("/summary") {
                val (userId, zone, range) = call.analyticsRequest(service)
                val s = service.summary(userId, range, zone)
                call.respond(HttpStatusCode.OK, ApiResponse.success(SummaryResponse(
                    s.range.dto(), s.current.dto(), s.spendCount, s.previousRange.dto(), s.previous.dto(), s.spentChangePct?.toPlainString()
                ), call.requestId))
            }
            get("/series") {
                val (userId, zone, range) = call.analyticsRequest(service)
                val bucket = call.enumParam<Bucket>("bucket") ?: Bucket.DAY
                val points = service.series(userId, range, bucket, zone, call.enumParam<TransactionCategory>("category"))
                call.respond(HttpStatusCode.OK, ApiResponse.success(SeriesResponse(
                    range.dto(), bucket.name.lowercase(), points.map { SeriesPointDto(it.start.toString(), it.money.spent.money(), it.money.received.money()) }
                ), call.requestId))
            }
            get("/categories") {
                val (userId, zone, range) = call.analyticsRequest(service)
                val rows = service.categories(userId, range, zone)
                call.respond(HttpStatusCode.OK, ApiResponse.success(CategoriesResponse(
                    range.dto(), rows.map { CategoryDto(it.category.name.lowercase(), it.spent.money(), it.share, it.count, it.previousSpent.money()) }
                ), call.requestId))
            }
            get("/merchants") {
                val (userId, zone, range) = call.analyticsRequest(service)
                val rows = service.merchants(userId, range, zone, call.limitParam(default = 10, max = 50), call.enumParam<TransactionCategory>("category"))
                call.respond(HttpStatusCode.OK, ApiResponse.success(MerchantsResponse(
                    range.dto(), rows.map { MerchantDto(it.merchant, it.spent.money(), it.count, it.category.name.lowercase()) }
                ), call.requestId))
            }
        }
    }
}

private data class AnalyticsRequest(val userId: java.util.UUID, val zone: java.time.ZoneId, val range: DateRange)

/** Both dates or neither; neither = this month so far. Range errors become 400s naming the parameter. */
private fun ApplicationCall.analyticsRequest(service: AnalyticsService): AnalyticsRequest {
    val zone = userZone
    val from = dateParam("from")
    val to = dateParam("to")
    val range = when {
        from == null && to == null -> service.defaultRange(zone)
        from != null && to != null -> DateRange(from, to)
        else -> throw IllegalArgumentException("${if (from == null) "from" else "to"}: give both from and to, or neither")
    }
    service.validate(range)
    return AnalyticsRequest(userPrincipal.userId, zone, range)
}
