package dev.adminos.api.domain.agent

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.CursorCodec
import dev.adminos.api.domain.common.Pagination
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

/** HTTP for anomalies only; the rules (ownership, feedback, audit) are in [AnomalyReviewService] (AOS-713). */
fun Route.anomalyRoutes(service: AnomalyReviewService) {
    authenticate("auth-jwt") {
        route("/anomalies") {

            // GET /api/v1/anomalies — list with status filter + cursor pagination
            get {
                val principal = call.userPrincipal
                val status = call.enumParam<AnomalyStatus>("status")
                val limit = call.limitParam()
                val (anomalies, total) = service.list(principal.userId, status, call.parameters["cursor"], limit + 1)

                val hasMore = anomalies.size > limit
                val page = if (hasMore) anomalies.take(limit) else anomalies
                val nextCursor = if (hasMore) page.lastOrNull()?.let { CursorCodec.encode(it.createdAt.toString()) } else null

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.paginated(
                        data = page.map { AnomalyResponse.from(it) },
                        pagination = Pagination(cursor = nextCursor, hasMore = hasMore, total = total),
                        requestId = call.requestId
                    )
                )
            }

            // GET /api/v1/anomalies/:id — get single anomaly by ID
            get("/{id}") {
                val anomaly = service.find(call.userPrincipal.userId, call.anomalyId())
                    ?: throw ApiException(HttpStatusCode.NotFound, ANOMALY_NOT_FOUND)
                call.respond(HttpStatusCode.OK, ApiResponse.success(AnomalyResponse.from(anomaly), call.requestId))
            }

            // PATCH /api/v1/anomalies/:id — resolve; the first answer also teaches that merchant's tolerance (AOS-713)
            patch("/{id}") {
                val anomalyId = call.anomalyId()
                val request = call.receive<AnomalyResolveRequest>()
                val newStatus = when (request.status) {
                    "confirmed_safe" -> AnomalyStatus.CONFIRMED_SAFE
                    "confirmed_fraud" -> AnomalyStatus.CONFIRMED_FRAUD
                    "dismissed" -> AnomalyStatus.DISMISSED
                    else -> throw ApiException(
                        HttpStatusCode.BadRequest,
                        ApiError("ANOM_002", "Status must be 'confirmed_safe', 'confirmed_fraud', or 'dismissed'")
                    )
                }
                when (val outcome = service.resolve(call.userPrincipal.userId, anomalyId, newStatus)) {
                    AnomalyResolution.NotFound -> throw ApiException(HttpStatusCode.NotFound, ANOMALY_NOT_FOUND)
                    is AnomalyResolution.Resolved ->
                        call.respond(HttpStatusCode.OK, ApiResponse.success(AnomalyResponse.from(outcome.anomaly), call.requestId))
                }
            }
        }
    }
}

/** The `{id}` path parameter; a malformed one is a 400 with the not-found code (unchanged from AOS-505). */
private fun ApplicationCall.anomalyId(): UUID = parameters["id"]?.let {
    try { UUID.fromString(it) } catch (_: IllegalArgumentException) { null }
} ?: throw ApiException(HttpStatusCode.BadRequest, ANOMALY_NOT_FOUND)

private val ANOMALY_NOT_FOUND = ApiError("ANOM_001", "Anomaly not found")
