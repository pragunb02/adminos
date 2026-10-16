package dev.adminos.api.domain.identity

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
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
import java.util.UUID

/** Auth provider for devices that may ingest SMS. Header-only bearer: never a browser cookie. */
const val DEVICE_INGEST_SMS = "device-ingest-sms"

fun Application.configureDeviceAuthentication(service: DeviceTokenService) {
    authentication {
        bearer(DEVICE_INGEST_SMS) {
            authenticate { credential -> service.authenticate(credential.token, Scopes.INGEST_SMS) }
        }
    }
}

val ApplicationCall.devicePrincipal: DevicePrincipal
    get() = principal<DevicePrincipal>() ?: throw ApiException(HttpStatusCode.Unauthorized, ApiError.TOKEN_INVALID)

@Serializable
data class CreateDeviceTokenRequest(val name: String, val scopes: List<String> = listOf(Scopes.INGEST_SMS))

@Serializable
data class DeviceTokenResponse(
    val id: String,
    val name: String,
    val scopes: List<String>,
    val createdAt: String,
    val expiresAt: String,
    val lastUsedAt: String?,
    val revokedAt: String?,
    /** Only present in the creation response, shown once. */
    val token: String? = null
) {
    companion object {
        fun from(t: DeviceToken, token: String? = null) = DeviceTokenResponse(
            t.id.toString(), t.name, t.scopes.sorted(), t.createdAt.toString(), t.expiresAt.toString(),
            t.lastUsedAt?.toString(), t.revokedAt?.toString(), token
        )
    }
}

/** Settings → devices: issue (token shown once), list, revoke. Requires a logged-in user. */
fun Route.deviceTokenRoutes(service: DeviceTokenService) {
    authenticate("auth-jwt") {
        route("/device-tokens") {
            post {
                val req = call.receive<CreateDeviceTokenRequest>()
                val issued = service.issue(call.userPrincipal.userId, req.name, req.scopes.toSet())
                call.respond(HttpStatusCode.Created, ApiResponse.success(DeviceTokenResponse.from(issued.record, issued.token), call.requestId))
            }
            get {
                call.respond(HttpStatusCode.OK, ApiResponse.success(service.list(call.userPrincipal.userId).map { DeviceTokenResponse.from(it) }, call.requestId))
            }
            delete("/{id}") {
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: throw ApiException(HttpStatusCode.BadRequest, ApiError("VALIDATION_003", "Invalid token id"))
                if (!service.revoke(call.userPrincipal.userId, id)) throw ApiException(HttpStatusCode.NotFound, ApiError("DEVICE_001", "Device token not found"))
                call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("revoked" to id.toString()), call.requestId))
            }
        }
    }
}
