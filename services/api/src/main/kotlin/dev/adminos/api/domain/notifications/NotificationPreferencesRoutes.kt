package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.LocalTime

fun Route.notificationPreferencesRoutes(
    preferencesRepository: NotificationPreferencesRepository
) {
    authenticate("auth-jwt") {
        route("/users/me/notification-preferences") {

            // GET /api/v1/users/me/notification-preferences
            get {
                val principal = call.userPrincipal
                val prefs = preferencesRepository.findByUserId(principal.userId)
                    ?: NotificationPreferences.defaultFor(principal.userId).also {
                        preferencesRepository.save(it)
                    }

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        NotificationPreferencesResponse.from(prefs),
                        call.requestId
                    )
                )
            }

            // PATCH /api/v1/users/me/notification-preferences
            patch {
                val principal = call.userPrincipal
                val request = call.receive<UpdatePreferencesRequest>()

                val existing = preferencesRepository.findByUserId(principal.userId)
                    ?: NotificationPreferences.defaultFor(principal.userId).also {
                        preferencesRepository.save(it)
                    }

                val updated = existing.copy(
                    weeklyBriefing = request.weeklyBriefing ?: existing.weeklyBriefing,
                    briefingDay = request.briefingDay?.let { Weekday.valueOf(it.uppercase()) }
                        ?: existing.briefingDay,
                    briefingTime = request.briefingTime?.let { LocalTime.parse(it) }
                        ?: existing.briefingTime,
                    billReminders = request.billReminders ?: existing.billReminders,
                    billReminderDays = request.billReminderDays?.also { days ->
                        require(days.all { it in 1..30 }) { "billReminderDays must be between 1 and 30" }
                        require(days.size <= 5) { "Maximum 5 reminder days allowed" }
                    } ?: existing.billReminderDays,
                    anomalyAlerts = request.anomalyAlerts ?: existing.anomalyAlerts,
                    subscriptionFlags = request.subscriptionFlags ?: existing.subscriptionFlags,
                    emailNotifications = request.emailNotifications ?: existing.emailNotifications,
                    pushNotifications = request.pushNotifications ?: existing.pushNotifications,
                    quietHoursStart = request.quietHoursStart?.let { LocalTime.parse(it) }
                        ?: existing.quietHoursStart,
                    quietHoursEnd = request.quietHoursEnd?.let { LocalTime.parse(it) }
                        ?: existing.quietHoursEnd
                )

                val saved = preferencesRepository.update(updated)

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        NotificationPreferencesResponse.from(saved),
                        call.requestId
                    )
                )
            }
        }
    }
}
