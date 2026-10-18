package dev.adminos.api.domain.coach.savings

import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.infrastructure.plugins.invalidParam
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import dev.adminos.api.infrastructure.plugins.userZone
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable data class SavingsMonthDto(val month: String, val income: String, val spent: String, val saved: String, val rate: String?, val partial: Boolean)
@Serializable data class HorizonDto(val years: Int, val contributed: String, val futureValue: String, val inTodaysMoney: String)
@Serializable data class ProjectionDto(val month: String, val monthlyAmount: String, val annualReturnPct: String, val inflationPct: String, val horizons: List<HorizonDto>, val formula: String)
@Serializable data class SavingsResponse(
    val months: List<SavingsMonthDto>,
    val headline: SavingsMonthDto?,
    val previous: SavingsMonthDto?,
    /** True when no month in the range had income: there's no rate to show yet. */
    val tooLittleData: Boolean,
    val projection: ProjectionDto?,
    val defaults: AssumptionsDto
)
@Serializable data class AssumptionsDto(val annualReturnPct: String, val inflationPct: String)

private fun SavingsMonth.dto() = SavingsMonthDto(month.toString(), income.toMoney(), spent.toMoney(), saved.toMoney(), rate?.toPlainString(), partial)

/** `GET /coach/savings?months=12&annualReturnPct=12&inflationPct=6` (AOS-908). */
fun Route.savingsRoutes(service: SavingsService) {
    authenticate("auth-jwt") {
        get("/coach/savings") {
            val months = call.parameters["months"]?.let { raw -> raw.toIntOrNull() ?: invalidParam("months", raw.take(20), "${SavingsService.MIN_MONTHS}..${SavingsService.MAX_MONTHS}") }
                ?: SavingsService.DEFAULT_MONTHS
            if (months !in SavingsService.MIN_MONTHS..SavingsService.MAX_MONTHS) invalidParam("months", months.toString(), "${SavingsService.MIN_MONTHS}..${SavingsService.MAX_MONTHS}")
            val a = Assumptions(
                call.percent("annualReturnPct") ?: Assumptions.DEFAULT.annualReturnPct,
                call.percent("inflationPct") ?: Assumptions.DEFAULT.inflationPct
            )
            val r = service.report(call.userPrincipal.userId, call.userZone, months, a)
            call.respond(HttpStatusCode.OK, ApiResponse.success(SavingsResponse(
                months = r.months.map { it.dto() }, headline = r.headline?.dto(), previous = r.previous?.dto(), tooLittleData = r.headline == null,
                projection = r.projection?.let { p ->
                    ProjectionDto(p.month.toString(), p.monthlyAmount.toMoney(), p.assumptions.annualReturnPct.toPlainString(), p.assumptions.inflationPct.toPlainString(),
                        p.horizons.map { HorizonDto(it.years, it.contributed.toMoney(), it.futureValue.toMoney(), it.inTodaysMoney.toMoney()) }, Sip.FORMULA)
                },
                defaults = AssumptionsDto(Assumptions.DEFAULT.annualReturnPct.toPlainString(), Assumptions.DEFAULT.inflationPct.toPlainString())
            ), call.requestId))
        }
    }
}

private val PERCENT = Regex("\\d{1,2}(\\.\\d{1,2})?")

private fun ApplicationCall.percent(name: String): BigDecimal? =
    parameters[name]?.let { raw -> if (raw.matches(PERCENT)) BigDecimal(raw) else invalidParam(name, raw.take(20), "a percent like 12 or 6.5") }
