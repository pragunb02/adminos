package dev.adminos.api.domain.agent

import dev.adminos.api.domain.audit.ActorType
import dev.adminos.api.domain.audit.AuditActions
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.financial.SubscriptionRepository
import dev.adminos.api.domain.financial.SubscriptionResponse
import dev.adminos.api.domain.financial.UsageStatus
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

fun Route.subscriptionAgentRoutes(
    subscriptionRepository: SubscriptionRepository,
    auditService: AuditService
) {
    authenticate("auth-jwt") {
        route("/subscriptions") {

            // PATCH /api/v1/subscriptions/:id/dismiss — dismiss flag
            patch("/{id}/dismiss") {
                val principal = call.userPrincipal
                val subId = call.parameters["id"]?.let {
                    try { UUID.fromString(it) } catch (_: Exception) { null }
                } ?: throw ApiException(HttpStatusCode.BadRequest, ApiError.SUBSCRIPTION_NOT_FOUND)

                val sub = subscriptionRepository.findById(subId)
                    ?: throw ApiException(HttpStatusCode.NotFound, ApiError.SUBSCRIPTION_NOT_FOUND)

                if (sub.userId != principal.userId) {
                    throw ApiException(HttpStatusCode.NotFound, ApiError.SUBSCRIPTION_NOT_FOUND)
                }

                val updated = subscriptionRepository.update(sub.copy(
                    usageStatus = UsageStatus.ACTIVE,
                    isFlagged = false,
                    flagDismissedAt = Instant.now()
                ))

                auditService.log(
                    userId = principal.userId,
                    actor = ActorType.USER,
                    action = AuditActions.SUBSCRIPTION_KEEP,
                    entityType = "subscription",
                    entityId = subId,
                    beforeState = JsonObject(mapOf("is_flagged" to JsonPrimitive(true))),
                    afterState = JsonObject(mapOf("is_flagged" to JsonPrimitive(false), "flag_dismissed_at" to JsonPrimitive(updated.flagDismissedAt.toString())))
                )

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(SubscriptionResponse.from(updated), call.requestId)
                )
            }

            // POST /api/v1/subscriptions/:id/cancel — enqueue cancellation draft
            post("/{id}/cancel") {
                val principal = call.userPrincipal
                val subId = call.parameters["id"]?.let {
                    try { UUID.fromString(it) } catch (_: Exception) { null }
                } ?: throw ApiException(HttpStatusCode.BadRequest, ApiError.SUBSCRIPTION_NOT_FOUND)

                val sub = subscriptionRepository.findById(subId)
                    ?: throw ApiException(HttpStatusCode.NotFound, ApiError.SUBSCRIPTION_NOT_FOUND)

                if (sub.userId != principal.userId) {
                    throw ApiException(HttpStatusCode.NotFound, ApiError.SUBSCRIPTION_NOT_FOUND)
                }

                // In production, this would enqueue a cancellation_draft job to Redis.
                // For MVP, we return a stub response indicating the job was queued.
                auditService.log(
                    userId = principal.userId,
                    actor = ActorType.USER,
                    action = AuditActions.SUBSCRIPTION_CANCEL,
                    entityType = "subscription",
                    entityId = subId
                )

                call.respond(
                    HttpStatusCode.Accepted,
                    ApiResponse.success(
                        CancellationQueuedResponse(
                            subscriptionId = subId.toString(),
                            status = "queued",
                            message = "Cancellation draft generation has been queued"
                        ),
                        call.requestId
                    )
                )
            }
        }
    }
}

@Serializable
data class CancellationQueuedResponse(
    val subscriptionId: String,
    val status: String,
    val message: String
)
