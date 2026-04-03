package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.notificationListRoutes(notificationRepository: NotificationRepository) {
    authenticate("auth-jwt") {
        route("/notifications") {
            get {
                val principal = call.userPrincipal
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50

                val notifications = notificationRepository.findByUserId(principal.userId, limit)
                val response = notifications.map { NotificationResponse.from(it) }

                call.respond(HttpStatusCode.OK, ApiResponse.success(response, call.requestId))
            }
        }
    }
}
