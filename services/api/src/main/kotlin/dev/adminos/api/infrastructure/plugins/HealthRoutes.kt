package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.domain.common.ApiResponse
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureHealthRoutes() {
    routing {
        get("/health") {
            call.respond(ApiResponse.success(mapOf("status" to "ok"), call.requestId))
        }
    }
}
