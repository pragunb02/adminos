package dev.adminos.api.domain.notifications.push

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class PushKeysRequest(val p256dh: String, val auth: String)

/** The browser's `PushSubscription.toJSON()`, plus a label for Settings. */
@Serializable
data class PushSubscribeRequest(val endpoint: String, val keys: PushKeysRequest, val label: String? = null)

@Serializable
data class PushUnsubscribeRequest(val endpoint: String)

@Serializable
data class PushUnsubscribeResponse(val removed: Boolean)

@Serializable
data class PushDeviceResponse(val id: String, val label: String?, val createdAt: String, val lastSuccessAt: String?)

@Serializable
data class PushStatusResponse(val enabled: Boolean, val publicKey: String?, val devices: List<PushDeviceResponse>)

@Serializable
data class PushTestResponse(val devices: Int, val delivered: Boolean, val detail: String)

private fun PushSubscription.toResponse() = PushDeviceResponse(id.toString(), label, createdAt.toString(), lastSuccessAt?.toString())

/**
 * Web Push per device (AOS-510), signed-in users only:
 * - `GET /push` — is push set up, the public key to subscribe with, this user's devices
 * - `POST /push/subscriptions` — turn push on for this device (201)
 * - `DELETE /push/subscriptions` — turn it off (200; 404 if it wasn't on)
 * - `POST /push/test` — send a test push to this user's devices now
 */
fun Route.pushRoutes(service: PushSubscriptionService) {
    authenticate("auth-jwt") {
        route("/push") {
            get {
                val status = service.status(call.userPrincipal.userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(
                    PushStatusResponse(status.enabled, status.publicKey, status.devices.map { it.toResponse() }), call.requestId))
            }
            post("/subscriptions") {
                val request = call.receive<PushSubscribeRequest>()
                val saved = guarded {
                    service.register(call.userPrincipal.userId, PushEndpoint(request.endpoint), PushKeys(request.keys.p256dh, request.keys.auth), request.label)
                }
                call.respond(HttpStatusCode.Created, ApiResponse.success(saved.toResponse(), call.requestId))
            }
            delete("/subscriptions") {
                val request = call.receive<PushUnsubscribeRequest>()
                if (!service.unregister(call.userPrincipal.userId, PushEndpoint(request.endpoint))) {
                    throw ApiException(HttpStatusCode.NotFound, ApiError("PUSH_002", "Push isn't turned on for this device"))
                }
                call.respond(HttpStatusCode.OK, ApiResponse.success(PushUnsubscribeResponse(removed = true), call.requestId))
            }
            post("/test") {
                val result = guarded { service.sendTest(call.userPrincipal.userId) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(PushTestResponse(result.devices, result.delivered, result.detail), call.requestId))
            }
        }
    }
}

/** Push without server keys is a 503 the Settings page explains, not a 500. */
private suspend fun <T> guarded(work: suspend () -> T): T = try {
    work()
} catch (e: PushNotConfiguredException) {
    throw ApiException(HttpStatusCode.ServiceUnavailable, ApiError("PUSH_001", e.message.orEmpty()))
}
