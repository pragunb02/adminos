package dev.adminos.api.domain.notifications

import io.ktor.server.application.*
import io.ktor.server.routing.*
import dev.adminos.api.domain.notifications.push.PushSubscriptionService
import dev.adminos.api.domain.notifications.push.pushRoutes
import org.koin.ktor.ext.inject

/**
 * Notification domain module — registers preference routes, the notification list and Web Push (AOS-510).
 */
fun Application.notificationModule() {
    val preferencesRepository: NotificationPreferencesRepository by inject()
    val notificationRepository: NotificationRepository by inject()
    val pushService: PushSubscriptionService by inject()

    routing {
        route("/api/v1") {
            notificationPreferencesRoutes(preferencesRepository)
            notificationListRoutes(notificationRepository)
            pushRoutes(pushService)
        }
    }
}
