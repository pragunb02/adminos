package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory

class ApiException(
    val statusCode: HttpStatusCode,
    val error: ApiError
) : RuntimeException(error.message)

private val logger = LoggerFactory.getLogger("ErrorHandling")

fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(
                cause.statusCode,
                ApiResponse.error(cause.error, call.requestId)
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse.error(
                    ApiError("VALIDATION_001", publicMessage(cause.message, "Invalid input")),
                    call.requestId
                )
            )
        }
        // Fix #12: DateTimeParseException → 400 instead of 500
        exception<java.time.format.DateTimeParseException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse.error(
                    ApiError("VALIDATION_002", "Invalid date/time format"), // the parser's text quotes the input (AUD-15)
                    call.requestId
                )
            )
        }
        // Fix #12: DateTimeException (covers ZoneId.of with invalid timezone)
        exception<java.time.DateTimeException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse.error(
                    ApiError("VALIDATION_003", "Invalid date/time value"),
                    call.requestId
                )
            )
        }
        // Undecodable request bodies (bad JSON, wrong types such as a number where a string is
        // required) are client errors, not 500s.
        exception<io.ktor.server.plugins.BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse.error(ApiError("VALIDATION_004", "Malformed request body: ${publicMessage(rootMessage(cause), "invalid")}"), call.requestId)
            )
        }
        // Auth challenges (missing/invalid JWT) carry no body by default; clients always get the envelope.
        status(HttpStatusCode.Unauthorized) { call, status ->
            call.respond(status, ApiResponse.error(ApiError.TOKEN_INVALID, call.requestId))
        }
        // An upload over the route's formFieldLimit (statements, documents): Ktor's multipart parser fails the
        // call with an IOException. That's the client's file being too large, not a server error (AOS-507).
        exception<java.io.IOException> { call, cause ->
            if (cause.message?.contains("exceeds limit") == true) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiResponse.error(ApiError("FILE_TOO_LARGE", "The file is too large (at most 20 MB)"), call.requestId))
            } else {
                logger.error("Unhandled I/O exception", cause)
                call.respond(HttpStatusCode.InternalServerError, ApiResponse.error(ApiError.INTERNAL_ERROR, call.requestId))
            }
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiResponse.error(ApiError.INTERNAL_ERROR, call.requestId)
            )
        }
    }
}

/**
 * What of an exception's message may reach a client (AOS-618, AUD-15). Our own `require(...)` messages are written
 * for people and name the field ("to: …"). Library messages can leak internals: class names
 * ("No enum constant dev.adminos…"), JDK wording that quotes the input, or long dumps. Those become [fallback].
 * Control characters are removed either way, so nothing can forge log lines or headers.
 */
internal fun publicMessage(raw: String?, fallback: String): String {
    val text = raw?.replace(Regex("[\\p{Cntrl}]"), " ")?.trim().orEmpty()
    if (text.isEmpty() || text.length > 300) return fallback
    if (INTERNAL_DETAIL.any { it.containsMatchIn(text) }) return fallback
    return text.replace(QUALIFIED_NAME, "the request")
}

/** A fully-qualified class name, e.g. a serializer's `'dev.adminos.api…CashRequest'`: replaced, never shown. */
private val QUALIFIED_NAME = Regex("'?\\b(dev\\.adminos|java|javax|kotlin|kotlinx|io\\.ktor|org|com)(\\.[\\w$]+){2,}'?")

private val INTERNAL_DETAIL = listOf(
    Regex("No enum constant|Invalid UUID string|For input string|UUID string too large|Index \\d+ out of bounds"),
    Regex("(?i)exception|stack ?trace|\\bat [\\w.$]+\\("),
)

/** Innermost message of an exception chain (the decoder's message names the bad field). */
private fun rootMessage(t: Throwable): String {
    var c: Throwable = t
    while (true) {
        val next = c.cause ?: break
        if (next === c) break
        c = next
    }
    return (c.message ?: "invalid").lineSequence().first().take(200)
}
