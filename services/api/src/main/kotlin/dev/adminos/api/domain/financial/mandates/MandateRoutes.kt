package dev.adminos.api.domain.financial.mandates

import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.domain.ingestion.pipeline.TransactionCreatedListener
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class MandateResponse(
    val id: String, val payee: String, val rail: String, val amount: String?, val frequency: String, val status: String,
    val nextDebitOn: String?, val nextDebitAmount: String?, val lastDebitAt: String?, val lastDebitAmount: String?,
    val validUntil: String?, val dormant: Boolean, val hasReference: Boolean
)

private fun MandateView.toResponse() = MandateResponse(
    id = mandate.id.toString(), payee = mandate.payee, rail = mandate.rail.name.lowercase(), amount = mandate.amount?.toMoney(),
    frequency = mandate.frequency.name.lowercase(), status = status.name.lowercase(), nextDebitOn = nextDebitOn?.toString(),
    nextDebitAmount = mandate.nextDebitAmount?.toMoney(), lastDebitAt = mandate.lastDebitAt?.toString(), lastDebitAmount = mandate.lastDebitAmount?.toMoney(),
    validUntil = mandate.validUntil?.toString(), dormant = dormant, hasReference = mandate.umn != null // the UMN itself isn't shown
)

/** `GET /api/v1/mandates` — the user's AutoPay / e-NACH / card mandates as of their today (AOS-502). */
fun Route.mandateRoutes(service: MandateService) {
    authenticate("auth-jwt") {
        get("/mandates") {
            val p = call.userPrincipal
            val list = service.list(p.userId, LocalDate.now(p.zone))
            call.respond(HttpStatusCode.OK, ApiResponse.success(list.map { it.toResponse() }, call.requestId))
        }
    }
}

/** After each stored transaction: does it pay a mandate? (AOS-502; see [MandateDebitMatcher]). */
class MandateDebitListener(private val service: MandateService) : TransactionCreatedListener {
    override suspend fun onCreated(transaction: dev.adminos.api.domain.financial.Transaction) {
        service.recordDebit(transaction)
    }
}
