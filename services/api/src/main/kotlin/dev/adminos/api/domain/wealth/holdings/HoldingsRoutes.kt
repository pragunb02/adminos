package dev.adminos.api.domain.wealth.holdings

import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.domain.wealth.imports.dto
import dev.adminos.api.domain.wealth.imports.HoldingStatusDto
import dev.adminos.api.domain.wealth.imports.ImportedHolding
import dev.adminos.api.domain.wealth.prices.PriceSource
import dev.adminos.api.domain.wealth.returns.ReturnBasis
import dev.adminos.api.domain.wealth.returns.ReturnFigure
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import dev.adminos.api.infrastructure.plugins.userZone
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** A return as shown: [percent] to 1 decimal ("12.3"), absolute under a year, annual XIRR from a year; [reason] when none. */
@Serializable data class ReturnDto(val basis: String, val percent: String?, val days: Long, val reason: String?)
@Serializable data class HowDto(
    val units: String, val price: String?, val priceDate: String?, val priceSource: String?, val stale: Boolean, val staleAfterDays: Long?,
    val lots: Int, val cashFlows: Int, val costMethod: String, val steps: List<String>
)
@Serializable data class HoldingDto(
    val scheme: String, val folio: String, val amc: String, val plan: String?, val isin: String?, val value: String, val invested: String, val gain: String,
    val gainPercent: String?, val realised: String, val dividends: String, val valuedAtCost: Boolean, val ret: ReturnDto, val issues: List<String>, val how: HowDto
)
@Serializable data class GroupDto(val assetClass: String, val value: String, val invested: String, val holdings: List<HoldingDto>)
@Serializable data class WealthOverviewDto(
    val asOf: String, val pricesAsOf: String?, val stale: Int, val valuedAtCost: Int, val value: String, val invested: String, val gain: String,
    val gainPercent: String?, val realised: String, val ret: ReturnDto, val groups: List<GroupDto>, val needsReview: List<HoldingStatusDto>
)

internal fun percent(ratio: BigDecimal?): String? = ratio?.multiply(BigDecimal(100))?.setScale(1, RoundingMode.HALF_EVEN)?.toPlainString()

internal fun ReturnFigure.dto() = ReturnDto(basis.name.lowercase(), percent(shown), days, reason)

private fun sourceText(s: PriceSource) = when (s) { PriceSource.AMFI -> "AMFI daily NAV file"; PriceSource.STATEMENT -> "your statement's valuation" }

internal fun HoldingView.dto(): HoldingDto {
    val p = price
    val steps = buildList {
        add("Units held: ${lots.units} (from ${flows} transactions in your imported statements)")
        if (p != null) {
            add("Price: NAV ₹${p.observation.price} on ${p.observation.date}, from the ${sourceText(p.observation.source)}" + if (p.stale) " (older than ${p.staleAfterDays} days, so marked stale)" else "")
            add("Value = units × price = ${value.toMoney()}")
        } else {
            add("No price yet, so it is valued at what it cost")
        }
        add("Invested = what the units still held cost, oldest first (FIFO), including stamp duty = ${invested.toMoney()}")
        add("Gain = value − invested = ${gain.toMoney()}")
        add(when (ret.basis) {
            ReturnBasis.ABSOLUTE -> "Return: held under a year (${ret.days} days), so shown as an absolute return, not a yearly rate"
            ReturnBasis.ANNUALISED -> "Return: XIRR, a yearly rate that accounts for when each amount went in and came out"
            ReturnBasis.NONE -> "Return: not shown. ${ret.reason ?: ""}".trim()
        })
    }
    return HoldingDto(
        scheme = instrument.name, folio = account.identifier.masked, amc = account.institution, plan = instrument.mfPlan?.wire, isin = instrument.isin,
        value = value.toMoney(), invested = invested.toMoney(), gain = gain.toMoney(), gainPercent = percent(gainRatio),
        realised = lots.realisedInr.toMoney(), dividends = lots.dividends.toMoney(), valuedAtCost = valuedAtCost, ret = ret.dto(),
        issues = lots.issues.map { it.text }.sorted(),
        how = HowDto(lots.units.toString(), p?.observation?.price?.toString(), p?.observation?.date?.toString(), p?.observation?.source?.wire,
            p?.stale ?: false, p?.staleAfterDays, lots.open.size, flows, "fifo", steps)
    )
}

internal fun WealthOverview.dto() = WealthOverviewDto(
    asOf = asOf.toString(), pricesAsOf = pricesAsOf?.toString(), stale = stale, valuedAtCost = valuedAtCost, value = value.toMoney(),
    invested = invested.toMoney(), gain = gain.toMoney(), gainPercent = percent(if (invested.signum() > 0) gain.divide(invested, java.math.MathContext.DECIMAL128) else null),
    realised = realised.toMoney(), ret = ret.dto(),
    groups = groups.map { g -> GroupDto(g.assetClass.wire, g.value.toMoney(), g.invested.toMoney(), g.holdings.map { it.dto() }) },
    needsReview = blocked.map { ImportedHolding(it.folioMasked, it.amc, it.status).dto() }
)

/** `GET /wealth/holdings`: the Wealth overview on today's date in the owner's zone (AOS-820). Read-only. */
fun Route.holdingsRoutes(service: HoldingsService) {
    authenticate("auth-jwt") {
        get("/wealth/holdings") {
            val overview = service.overview(call.userPrincipal.userId, LocalDate.now(call.userZone))
            call.respond(HttpStatusCode.OK, ApiResponse.success(overview.dto(), call.requestId))
        }
    }
}
