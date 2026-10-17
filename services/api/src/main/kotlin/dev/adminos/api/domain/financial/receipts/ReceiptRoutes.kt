package dev.adminos.api.domain.financial.receipts

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.invalidParam
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.UUID

@Serializable
data class FeeLineResponse(val kind: String, val amount: String, val orders: Int)

@Serializable
data class AppFeesResponse(
    val app: String, val vertical: String, val orders: Int, val cancelledOrders: Int, val items: Int,
    val orderTotal: String, val hiddenFees: String, val tips: String, val feeShare: String, val fees: List<FeeLineResponse>,
    val matchedOrders: Int, val unmatchedOrders: Int, val noPaymentOrders: Int,
    val transactionIds: List<String>, val receiptIds: List<String>
)

@Serializable
data class FeeTotalsResponse(
    val orders: Int, val cancelledOrders: Int, val items: Int, val orderTotal: String,
    val hiddenFees: String, val tips: String, val feeShare: String
)

@Serializable
data class MonthFeesResponse(val month: String, val totals: FeeTotalsResponse, val apps: List<AppFeesResponse>)

@Serializable
data class LeakFeesResponse(val from: String, val to: String, val currency: String, val months: List<MonthFeesResponse>)

@Serializable
data class ReceiptFeeResponse(val kind: String, val amount: String)

@Serializable
data class ReceiptResponse(
    val id: String, val app: String, val vertical: String, val orderedAt: String, val status: String,
    val orderTotal: String?, val itemCount: Int?, val paymentMode: String, val paidAmount: String?, val refunded: String,
    val fees: List<ReceiptFeeResponse>, val hiddenFees: String, val tips: String, val counts: Boolean,
    val matchState: String, val transactionId: String?, val matchReason: String?, val matchedBy: String?
)

@Serializable
data class ConfirmReceiptMatchRequest(val transactionId: String)

private fun FeeLine.toResponse() = FeeLineResponse(kind.name.lowercase(), amount.toMoney(), orders)

private fun AppFees.toResponse() = AppFeesResponse(
    app, vertical.name.lowercase(), orders, cancelledOrders, items, orderTotal.toMoney(), hiddenFees.toMoney(), tips.toMoney(),
    feeShare.toPlainString(), fees.map { it.toResponse() }, matchedOrders, unmatchedOrders, noPaymentOrders,
    transactionIds.map { it.toString() }, receiptIds.map { it.toString() }
)

private fun FeeTotals.toResponse() = FeeTotalsResponse(orders, cancelledOrders, items, orderTotal.toMoney(), hiddenFees.toMoney(), tips.toMoney(), feeShare.toPlainString())

internal fun OrderReceipt.toResponse() = ReceiptResponse(
    id = id.toString(), app = app, vertical = vertical.name.lowercase(), orderedAt = orderedAt.toString(), status = status.name.lowercase(),
    orderTotal = orderTotal?.toMoney(), itemCount = itemCount, paymentMode = paymentMode.name.lowercase(), paidAmount = paidAmount?.toMoney(),
    refunded = refunded.toMoney(),
    fees = FeeKind.entries.mapNotNull { k -> fees[k]?.let { ReceiptFeeResponse(k.name.lowercase(), it.toMoney()) } },
    hiddenFees = hiddenFees.toMoney(), tips = tips.toMoney(), counts = counts,
    matchState = match.state.name.lowercase(), transactionId = match.transactionId?.toString(), matchReason = match.reason,
    matchedBy = match.matchedBy?.name?.lowercase()
)

/**
 * Order receipts and their hidden fees (AOS-903, D-88). Owner-only, scoped to the signed-in user.
 * - `GET /leaks/fees?from=YYYY-MM&to=YYYY-MM`: hidden fees per month and app (default: this month; at most 12)
 * - `GET /receipts?month=YYYY-MM&app=…`: the orders behind the numbers
 * - `POST /receipts/{id}/match` `{transactionId}`: this payment paid the order
 * - `DELETE /receipts/{id}/match`: the linked or suggested payment is not this order's (remembered)
 * Mutations rely on the session cookie being SameSite=Strict (no cross-site requests carry it).
 */
fun Route.receiptRoutes(fees: HiddenFeesService, matching: ReceiptMatchingService) {
    authenticate("auth-jwt") {
        get("/leaks/fees") {
            val p = call.userPrincipal
            val thisMonth = YearMonth.from(LocalDate.now(p.zone))
            val from = call.monthParam("from", thisMonth)
            val to = call.monthParam("to", if (call.parameters["from"] != null) from else thisMonth)
            if (to.isBefore(from)) invalidParam("to", to.toString(), "a month on or after 'from'")
            if (from.plusMonths(MonthSpan.MAX_MONTHS - 1L).isBefore(to)) invalidParam("to", to.toString(), "at most ${MonthSpan.MAX_MONTHS} months after 'from'")
            val months = fees.feesByMonth(p.userId, MonthSpan(from, to), p.zone)
            call.respond(HttpStatusCode.OK, ApiResponse.success(LeakFeesResponse(
                from = from.toString(), to = to.toString(), currency = "INR",
                months = months.map { m -> MonthFeesResponse(m.month.toString(), m.totals.toResponse(), m.apps.map { it.toResponse() }) }
            ), call.requestId))
        }
        route("/receipts") {
            get {
                val p = call.userPrincipal
                val month = call.monthParam("month", YearMonth.from(LocalDate.now(p.zone)))
                val app = call.parameters["app"]?.trim()?.also { if (!it.matches(APP_NAME)) invalidParam("app", it.take(40), "an app name, e.g. Swiggy") }
                val list = fees.receiptsIn(p.userId, month, p.zone, app)
                call.respond(HttpStatusCode.OK, ApiResponse.success(list.map { it.toResponse() }, call.requestId))
            }
            post("/{id}/match") {
                val id = call.receiptId()
                val body = call.receive<ConfirmReceiptMatchRequest>()
                val txn = runCatching { UUID.fromString(body.transactionId) }.getOrElse { invalidParam("transactionId", body.transactionId.take(40), "a UUID") }
                val updated = decide { matching.confirm(call.userPrincipal.userId, id, txn) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(updated.toResponse(), call.requestId))
            }
            delete("/{id}/match") {
                val id = call.receiptId()
                val updated = decide { matching.reject(call.userPrincipal.userId, id) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(updated.toResponse(), call.requestId))
            }
        }
    }
}

private val APP_NAME = Regex("[A-Za-z][A-Za-z0-9 .'&-]{0,39}")

private fun ApplicationCall.receiptId(): UUID {
    val raw = parameters["id"].orEmpty()
    return runCatching { UUID.fromString(raw) }.getOrElse { invalidParam("id", raw.take(40), "a UUID") }
}

/** A `YYYY-MM` query parameter within five years of [default]; [default] when absent. */
private fun ApplicationCall.monthParam(name: String, default: YearMonth): YearMonth {
    val raw = parameters[name] ?: return default
    val month = try { YearMonth.parse(raw.trim()) } catch (e: DateTimeParseException) { invalidParam(name, raw.take(20), "a month like 2026-10") }
    if (month.isBefore(default.minusYears(5)) || month.isAfter(default.plusYears(5))) invalidParam(name, raw, "a month within five years of today")
    return month
}

private suspend fun <T> decide(action: suspend () -> T): T = try {
    action()
} catch (e: ReceiptMatchError) {
    throw when (e) {
        is ReceiptMatchError.ReceiptNotFound -> ApiException(HttpStatusCode.NotFound, ApiError("RECEIPT_001", e.reason))
        is ReceiptMatchError.PaymentNotFound -> ApiException(HttpStatusCode.NotFound, ApiError("RECEIPT_002", e.reason))
        is ReceiptMatchError.NotAPayment -> ApiException(HttpStatusCode.BadRequest, ApiError("RECEIPT_003", e.reason))
        is ReceiptMatchError.PaymentTaken -> ApiException(HttpStatusCode.Conflict, ApiError("RECEIPT_004", e.reason))
        is ReceiptMatchError.NothingToReject -> ApiException(HttpStatusCode.Conflict, ApiError("RECEIPT_005", e.reason))
    }
}
