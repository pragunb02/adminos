package dev.adminos.api.infrastructure.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/** The logger request lines go to (tests read it). */
internal const val REQUEST_LOGGER = "http"

/**
 * One line per request (AOS-628, AUD-24): status, method, **path without the query string** (search terms, OAuth
 * codes and states live there), time and request ID. Response headers such as `Location` (which can carry a
 * consent URL with its state) are never logged.
 */
fun Application.configureLogging() {
    install(CallLogging) {
        level = Level.INFO
        logger = LoggerFactory.getLogger(REQUEST_LOGGER)
        disableDefaultColors()
        format { call -> requestLogLine(call) }
    }
}

internal fun requestLogLine(call: ApplicationCall): String {
    val status = call.response.status()?.value?.toString() ?: "unhandled"
    return "$status ${call.request.httpMethod.value} ${call.request.path()} in ${call.processingTimeMillis()}ms [${call.requestId}]"
}
