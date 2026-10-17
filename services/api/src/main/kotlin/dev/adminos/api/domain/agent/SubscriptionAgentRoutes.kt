package dev.adminos.api.domain.agent

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.financial.SubscriptionResponse
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

/**
 * Subscription decisions (AOS-513): keep a flagged one, and how to cancel one. Logic lives in
 * [SubscriptionReviewService]; another user's subscription is simply not found.
 */
fun Route.subscriptionAgentRoutes(service: SubscriptionReviewService) {
    authenticate("auth-jwt") {
        route("/subscriptions") {
            // PATCH /api/v1/subscriptions/:id/dismiss — keep it (dismiss the flag)
            patch("/{id}/dismiss") {
                val updated = found { service.keep(call.userPrincipal.userId, subscriptionId(call.parameters["id"])) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(SubscriptionResponse.from(updated), call.requestId))
            }

            // POST /api/v1/subscriptions/:id/cancel — how to cancel it (AdminOS can't cancel for you)
            post("/{id}/cancel") {
                val g = found { service.cancellationGuidance(call.userPrincipal.userId, subscriptionId(call.parameters["id"])) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(
                    CancellationGuidanceResponse(g.subscription.id.toString(), g.via?.name?.lowercase(), g.steps), call.requestId))
            }
        }
    }
}

@Serializable
data class CancellationGuidanceResponse(
    val subscriptionId: String,
    /** "upi_autopay" / "nach" / "card_si" when a mandate pays it; null otherwise. */
    val via: String?,
    val steps: List<String>
)

/** A malformed id stays a 400 (as before AOS-513); an unknown or someone else's is a 404. */
private fun subscriptionId(raw: String?): UUID = try {
    UUID.fromString(raw)
} catch (e: IllegalArgumentException) {
    throw ApiException(HttpStatusCode.BadRequest, ApiError.SUBSCRIPTION_NOT_FOUND)
}

private suspend fun <T> found(work: suspend () -> T): T = try {
    work()
} catch (e: SubscriptionNotFoundException) {
    throw ApiException(HttpStatusCode.NotFound, ApiError.SUBSCRIPTION_NOT_FOUND)
}
