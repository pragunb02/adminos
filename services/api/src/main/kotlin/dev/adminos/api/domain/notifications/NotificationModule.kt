package dev.adminos.api.domain.notifications

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Notification domain module — registers preference routes and notification list.
 */
fun Application.notificationModule() {
    val preferencesRepository: NotificationPreferencesRepository by inject()
    val notificationRepository: NotificationRepository by inject()

    routing {
        route("/api/v1") {
            notificationPreferencesRoutes(preferencesRepository)
            notificationListRoutes(notificationRepository)
        }
    }
}
