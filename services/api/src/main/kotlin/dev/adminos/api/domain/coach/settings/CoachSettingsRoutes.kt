package dev.adminos.api.domain.coach.settings

import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable data class CoachSettingsResponse(val limitAlerts: Boolean)
@Serializable data class UpdateCoachSettingsRequest(val limitAlerts: Boolean? = null)

internal fun CoachSettings.dto() = CoachSettingsResponse(limitAlerts)

/** `GET` / `PATCH /coach/settings`: the coach's own switches (D-80: every nudge is configurable). */
fun Route.coachSettingsRoutes(service: CoachSettingsService) {
    authenticate("auth-jwt") {
        route("/coach/settings") {
            get { call.respond(HttpStatusCode.OK, ApiResponse.success(service.of(call.userPrincipal.userId).dto(), call.requestId)) }
            patch {
                val body = call.receive<UpdateCoachSettingsRequest>()
                val saved = service.update(call.userPrincipal.userId) { s -> s.copy(limitAlerts = body.limitAlerts ?: s.limitAlerts) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(saved.dto(), call.requestId))
            }
        }
    }
}
