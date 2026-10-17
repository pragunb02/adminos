package dev.adminos.api.domain.ask

import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject

/** "Ask your money" routes (AOS-710). The service is always wired; it answers 409 while `AI_ASK_ENABLED` is off. */
fun Application.askModule() {
    val service: AskService by inject()
    val feedback: AskFeedbackService by inject()
    routing {
        route("/api/v1") { askRoutes(service, feedback) }
    }
}
