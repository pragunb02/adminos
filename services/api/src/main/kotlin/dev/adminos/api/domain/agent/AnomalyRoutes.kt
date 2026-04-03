package dev.adminos.api.domain.agent

import dev.adminos.api.domain.audit.ActorType
import dev.adminos.api.domain.audit.AuditActions
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.Pagination
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

fun Route.anomalyRoutes(
    anomalyRepository: AnomalyRepository,
    auditService: AuditService
) {
    authenticate("auth-jwt") {
        route("/anomalies") {

            // GET /api/v1/anomalies — list with status filter + cursor pagination
            get {
                val principal = call.userPrincipal
                val status = call.parameters["status"]?.let {
                    try { AnomalyStatus.valueOf(it.uppercase()) } catch (_: Exception) { null }
                }
                val cursor = call.parameters["cursor"]
                val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)

                val anomalies = anomalyRepository.findByUserId(
                    userId = principal.userId,
                    status = status,
                    cursor = cursor,
                    limit = limit + 1
                )

                val hasMore = anomalies.size > limit
                val page = if (hasMore) anomalies.take(limit) else anomalies
                val nextCursor = if (hasMore) page.lastOrNull()?.createdAt?.toString() else null
                val total = anomalyRepository.countByUserId(principal.userId, status)

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.paginated(
                        data = page.map { AnomalyResponse.from(it) },
                        pagination = Pagination(cursor = nextCursor, hasMore = hasMore, total = total),
                        requestId = call.requestId
                    )
                )
            }

            // PATCH /api/v1/anomalies/:id — resolve anomaly
            patch("/{id}") {
                val principal = call.userPrincipal
                val anomalyId = call.parameters["id"]?.let {
                    try { UUID.fromString(it) } catch (_: Exception) { null }
                } ?: throw ApiException(HttpStatusCode.BadRequest, ANOMALY_NOT_FOUND)

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

                val anomaly = anomalyRepository.findById(anomalyId)
                    ?: throw ApiException(HttpStatusCode.NotFound, ANOMALY_NOT_FOUND)

                if (anomaly.userId != principal.userId) {
                    throw ApiException(HttpStatusCode.NotFound, ANOMALY_NOT_FOUND)
                }

                val beforeStatus = anomaly.status.name.lowercase()
                val updated = anomalyRepository.update(anomaly.copy(
                    status = newStatus,
                    resolvedAt = Instant.now(),
                    resolvedBy = AnomalyResolver.USER
                ))

                // Audit log
                val auditAction = when (newStatus) {
                    AnomalyStatus.CONFIRMED_SAFE -> AuditActions.ANOMALY_CONFIRM_SAFE
                    AnomalyStatus.CONFIRMED_FRAUD -> AuditActions.ANOMALY_CONFIRM_FRAUD
                    AnomalyStatus.DISMISSED -> "anomaly.dismiss"
                    else -> "anomaly.resolve"
                }
                auditService.log(
                    userId = principal.userId,
                    actor = ActorType.USER,
                    action = auditAction,
                    entityType = "anomaly",
                    entityId = anomalyId,
                    beforeState = JsonObject(mapOf("status" to JsonPrimitive(beforeStatus))),
                    afterState = JsonObject(mapOf("status" to JsonPrimitive(newStatus.name.lowercase())))
                )

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(AnomalyResponse.from(updated), call.requestId)
                )
            }
        }
    }
}

private val ANOMALY_NOT_FOUND = ApiError("ANOM_001", "Anomaly not found")
