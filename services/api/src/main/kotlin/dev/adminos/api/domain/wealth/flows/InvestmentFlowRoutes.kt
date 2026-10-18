package dev.adminos.api.domain.wealth.flows

import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.infrastructure.plugins.dateParam
import dev.adminos.api.infrastructure.plugins.invalidParam
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import dev.adminos.api.infrastructure.plugins.userZone
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Serializable data class InvestmentFlowDto(
    val transactionId: String, val date: String, val direction: String, val amount: String, val merchant: String?, val status: String,
    val matchedDate: String?, val matchedType: String?
)
@Serializable data class InvestmentFlowsResponse(
    val from: String, val to: String, val invested: String, val redeemed: String, val net: String, val unmatched: Int, val flows: List<InvestmentFlowDto>
)

/**
 * `GET /wealth/investment-flows?from&to` (AOS-815): the bank feed's money to and from investments in a date range
 * (default the last 90 days, at most 3 years), each with the ledger row it matched, or why it didn't. Unmatched and
 * ambiguous rows are for Review; they still never count as spend.
 */
fun Route.investmentFlowRoutes(service: InvestmentFlowService) {
    authenticate("auth-jwt") {
        get("/wealth/investment-flows") {
            val zone = call.userZone
            val today = LocalDate.now(zone)
            val to = call.dateParam("to") ?: today
            val from = call.dateParam("from") ?: to.minusDays(89)
            if (from.isAfter(to)) invalidParam("from", from.toString(), "a date on or before 'to'")
            if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) invalidParam("from", from.toString(), "a range of at most $MAX_DAYS days")
            val report = service.report(call.userPrincipal.userId, from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1), zone)
            call.respond(HttpStatusCode.OK, ApiResponse.success(InvestmentFlowsResponse(
                from.toString(), to.toString(), report.invested.toMoney(), report.redeemed.toMoney(), report.net.toMoney(), report.unmatched,
                report.flows.map { f -> InvestmentFlowDto(
                    transactionId = f.transaction.id.toString(), date = f.transaction.transactedAt.atZone(zone).toLocalDate().toString(),
                    direction = if (f.transaction.type == dev.adminos.api.domain.financial.TransactionType.DEBIT) "out" else "in",
                    amount = f.transaction.amount.toMoney(), merchant = f.transaction.merchantName ?: f.transaction.merchantRaw?.take(80),
                    status = f.status.wire, matchedDate = f.match?.tradeDate?.toString(), matchedType = f.match?.type?.wire
                ) }
            ), call.requestId))
        }
    }
}

private const val MAX_DAYS = 3 * 366L
