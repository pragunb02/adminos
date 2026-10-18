package dev.adminos.api.domain.financial.trips

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.domain.financial.TransactionResponse
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
import java.util.UUID

@Serializable
data class TripLegResponse(val date: String, val brand: String, val mode: String?, val transactionId: String?)

@Serializable
data class TripSummaryResponse(
    val id: String, val name: String, val named: Boolean, val start: String, val end: String, val total: String,
    val transactionCount: Int, val modes: List<String>, val legs: List<TripLegResponse>
)

@Serializable
data class TripYearResponse(val year: Int, val total: String, val currency: String, val trips: List<TripSummaryResponse>)

@Serializable
data class TripMemberResponse(val role: String, val refunded: String, val transaction: TransactionResponse)

@Serializable
data class TripDetailResponse(val trip: TripSummaryResponse, val members: List<TripMemberResponse>)

@Serializable
data class RenameTripRequest(val name: String)

@Serializable
data class AddTripTransactionRequest(val transactionId: String)

private fun Trip.toSummary() = TripSummaryResponse(
    id = id.toString(), name = name, named = named, start = start.toString(), end = end.toString(), total = total.toMoney(),
    transactionCount = members.size, modes = modes.map { it.name.lowercase() },
    legs = legs.map { TripLegResponse(it.date.toString(), it.brand, it.mode?.name?.lowercase(), it.transactionId?.toString()) }
)

private fun TripDetail.toResponse() = TripDetailResponse(
    trip.toSummary(),
    trip.members.mapNotNull { m -> transactions[m.transactionId]?.let { TripMemberResponse(m.role.name.lowercase(), m.refunded.toMoney(), TransactionResponse.from(it)) } }
)

/**
 * Trips (AOS-910, D-90). Owner-only, scoped to the signed-in user; another user's trip or transaction is 404.
 * Mutations rely on the SameSite=Strict session cookie and the request-body cap (D-92).
 */
fun Route.tripRoutes(service: TripService) {
    authenticate("auth-jwt") {
        route("/trips") {
            get {
                val p = call.userPrincipal
                val thisYear = service.today(p.zone).year
                val year = call.parameters["year"]?.let { raw ->
                    raw.toIntOrNull()?.takeIf { it in (thisYear - 5)..(thisYear + 1) } ?: invalidParam("year", raw.take(10), "a year within five years of today")
                } ?: thisYear
                val y = service.year(p.userId, year, p.zone)
                call.respond(HttpStatusCode.OK, ApiResponse.success(TripYearResponse(y.year, y.total.toMoney(), "INR", y.trips.map { it.toSummary() }), call.requestId))
            }
            get("/{id}") {
                val detail = decide { service.detail(call.userPrincipal.userId, call.uuid("id"), call.userPrincipal.zone) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(detail.toResponse(), call.requestId))
            }
            get("/{id}/candidates") {
                val list = decide { service.candidates(call.userPrincipal.userId, call.uuid("id"), call.userPrincipal.zone) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(list.map { TransactionResponse.from(it) }, call.requestId))
            }
            patch("/{id}") {
                val id = call.uuid("id")
                val body = call.receive<RenameTripRequest>()
                val trip = decide { service.rename(call.userPrincipal.userId, id, body.name, call.userPrincipal.zone) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(trip.toSummary(), call.requestId))
            }
            delete("/{id}") {
                val id = call.uuid("id")
                decide { service.dismiss(call.userPrincipal.userId, id, call.userPrincipal.zone) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("id" to id.toString()), call.requestId))
            }
            post("/{id}/transactions") {
                val id = call.uuid("id")
                val body = call.receive<AddTripTransactionRequest>()
                val txn = runCatching { UUID.fromString(body.transactionId) }.getOrElse { invalidParam("transactionId", body.transactionId.take(40), "a UUID") }
                val trip = decide { service.add(call.userPrincipal.userId, id, txn, call.userPrincipal.zone) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(trip.toSummary(), call.requestId))
            }
            delete("/{id}/transactions/{transactionId}") {
                val id = call.uuid("id")
                val txn = call.uuid("transactionId")
                val trip = decide { service.remove(call.userPrincipal.userId, id, txn, call.userPrincipal.zone) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(trip.toSummary(), call.requestId))
            }
        }
    }
}

private fun ApplicationCall.uuid(name: String): UUID {
    val raw = parameters[name].orEmpty()
    return runCatching { UUID.fromString(raw) }.getOrElse { invalidParam(name, raw.take(40), "a UUID") }
}

private suspend fun <T> decide(action: suspend () -> T): T = try {
    action()
} catch (e: TripError) {
    throw when (e) {
        is TripError.TripNotFound -> ApiException(HttpStatusCode.NotFound, ApiError("TRIP_001", e.reason))
        is TripError.TransactionNotFound -> ApiException(HttpStatusCode.NotFound, ApiError("TRIP_002", e.reason))
        is TripError.NotASpend -> ApiException(HttpStatusCode.BadRequest, ApiError("TRIP_003", e.reason))
    }
}
