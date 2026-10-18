package dev.adminos.api.domain.financial.paylater

import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.domain.common.sumMoney
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Clock

@Serializable
data class PayLaterBillResponse(val id: String, val amount: String, val minimumDue: String?, val dueDate: String, val status: String)

@Serializable
data class UnbilledPurchasesResponse(val amount: String, val count: Int, val transactionIds: List<String>)

@Serializable
data class BillGapResponse(val billId: String, val billAmount: String, val dueDate: String, val status: String, val recorded: String, val unrecorded: String)

@Serializable
data class ProviderDebtResponse(
    val provider: String, val outstanding: String, val nextDue: String?, val purchasesLast30Days: String,
    val unpaidBills: List<PayLaterBillResponse>, val unbilledPurchases: UnbilledPurchasesResponse, val gaps: List<BillGapResponse>
)

@Serializable
data class PayLaterResponse(val outstanding: String, val currency: String, val providers: List<ProviderDebtResponse>)

private fun ProviderDebt.toResponse() = ProviderDebtResponse(
    provider = provider, outstanding = outstanding.toMoney(), nextDue = nextDue?.toString(), purchasesLast30Days = purchasesLast30Days.toMoney(),
    unpaidBills = unpaidBills.map { PayLaterBillResponse(it.id.toString(), it.amount.toMoney(), it.minimumDue?.toMoney(), it.dueDate.toString(), it.status.name.lowercase()) },
    unbilledPurchases = UnbilledPurchasesResponse(unbilledPurchases.map { it.amount }.sumMoney().toMoney(), unbilledPurchases.size, unbilledPurchases.map { it.id.toString() }),
    gaps = gaps.map { BillGapResponse(it.bill.id.toString(), it.bill.amount.toMoney(), it.bill.dueDate.toString(), it.bill.status.name.lowercase(), it.recorded.toMoney(), it.unrecorded.toMoney()) }
)

/** `GET /api/v1/pay-later` — pay-later debt outstanding per provider (AOS-909), for the coach (E9). Owner-only. */
fun Route.payLaterRoutes(service: PayLaterService, clock: Clock = Clock.systemUTC()) {
    authenticate("auth-jwt") {
        get("/pay-later") {
            val s = service.summary(call.userPrincipal.userId, clock.instant())
            call.respond(HttpStatusCode.OK, ApiResponse.success(PayLaterResponse(s.outstanding.toMoney(), "INR", s.providers.map { it.toResponse() }), call.requestId))
        }
    }
}
