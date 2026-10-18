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

@Serializable data class CoachSettingsResponse(val limitAlerts: Boolean, val eveningDigest: Boolean, val digestTime: String, val digestSkipZeroDay: Boolean)
@Serializable data class UpdateCoachSettingsRequest(
    val limitAlerts: Boolean? = null,
    val eveningDigest: Boolean? = null,
    /** "HH:mm", local time. */
    val digestTime: String? = null,
    val digestSkipZeroDay: Boolean? = null
)

internal fun CoachSettings.dto() = CoachSettingsResponse(limitAlerts, eveningDigest, digestTime.toString(), digestSkipZeroDay)

private val HH_MM = Regex("([01]\\d|2[0-3]):[0-5]\\d")

private fun timeOf(raw: String): java.time.LocalTime {
    require(raw.matches(HH_MM)) { "digestTime: a time like 21:30" }
    return java.time.LocalTime.parse(raw)
}

/** `GET` / `PATCH /coach/settings`: the coach's own switches (D-80: every nudge is configurable). */
fun Route.coachSettingsRoutes(service: CoachSettingsService) {
    authenticate("auth-jwt") {
        route("/coach/settings") {
            get { call.respond(HttpStatusCode.OK, ApiResponse.success(service.of(call.userPrincipal.userId).dto(), call.requestId)) }
            patch {
                val body = call.receive<UpdateCoachSettingsRequest>()
                val time = body.digestTime?.let(::timeOf)
                val saved = service.update(call.userPrincipal.userId) { s ->
                    s.copy(
                        limitAlerts = body.limitAlerts ?: s.limitAlerts,
                        eveningDigest = body.eveningDigest ?: s.eveningDigest,
                        digestTime = time ?: s.digestTime,
                        digestSkipZeroDay = body.digestSkipZeroDay ?: s.digestSkipZeroDay
                    )
                }
                call.respond(HttpStatusCode.OK, ApiResponse.success(saved.dto(), call.requestId))
            }
        }
    }
}
