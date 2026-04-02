package dev.adminos.api.infrastructure.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.callid.*
import java.util.UUID

fun Application.configureRequestId() {
    install(CallId) {
        generate { "req_${UUID.randomUUID().toString().replace("-", "").take(12)}" }
        verify { it.isNotEmpty() }
    }
}

val ApplicationCall.requestId: String
    get() = callId ?: "req_unknown"
