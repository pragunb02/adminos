package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
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
        // Request bodies the route can't read are the client's error, never a 500 (AOS-633). The messages are
        // fixed: the decoder's own text quotes the input ("but had '1' instead") and names our classes (AUD-15).
        // Ktor's body errors extend IOException, so without these they fell into the 500 branch below.
        // A body that isn't JSON (a form, plain text, or none at all) on a route that reads JSON: Ktor finds no
        // converter for it. That's 415; a JSON body Ktor still can't turn into the route's type is a 400.
        exception<io.ktor.server.plugins.UnsupportedMediaTypeException> { call, _ ->
            call.respond(HttpStatusCode.UnsupportedMediaType, ApiResponse.error(UNSUPPORTED_BODY, call.requestId))
        }
        exception<io.ktor.server.plugins.PayloadTooLargeException> { call, _ ->
            call.respond(HttpStatusCode.PayloadTooLarge, ApiResponse.error(BODY_TOO_LARGE, call.requestId))
        }
        exception<io.ktor.server.plugins.ContentTransformationException> { call, _ ->
            if (call.sentJson()) call.respond(HttpStatusCode.BadRequest, ApiResponse.error(MALFORMED_BODY, call.requestId))
            else call.respond(HttpStatusCode.UnsupportedMediaType, ApiResponse.error(UNSUPPORTED_BODY, call.requestId))
        }
        // JSON that doesn't parse or doesn't fit the request type (Ktor wraps the decoder's error in this).
        exception<io.ktor.server.plugins.BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiResponse.error(MALFORMED_BODY, call.requestId))
        }
        // Auth challenges (missing/invalid JWT) carry no body by default; clients always get the envelope.
        status(HttpStatusCode.Unauthorized) { call, status ->
            val error = if (call.attributes.getOrNull(SecondFactorPending) == true) SECOND_FACTOR_REQUIRED else ApiError.TOKEN_INVALID
            call.respond(status, ApiResponse.error(error, call.requestId))
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
            val sql = sqlCause(cause)
            if (sql != null) {
                // A database error's message can carry the values of the failed statement (AOS-628): log its kind
                // and SQLState only, and where it was thrown from.
                logger.error("Unhandled database error: {} (SQLState {}) at {}", sql.javaClass.simpleName, sql.sqlState,
                    cause.stackTrace.firstOrNull { it.className.startsWith("dev.adminos") } ?: "unknown")
            } else {
                logger.error("Unhandled exception", cause)
            }
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

/** The first [java.sql.SQLException] in [t]'s cause chain, if any. */
internal fun sqlCause(t: Throwable): java.sql.SQLException? {
    var c: Throwable? = t
    val seen = mutableSetOf<Throwable>()
    while (c != null && seen.add(c)) {
        if (c is java.sql.SQLException) return c
        c = c.cause
    }
    return null
}

/** Whether the request says its body is JSON (`application/json` or a `+json` type). A bad header isn't. */
private fun ApplicationCall.sentJson(): Boolean {
    val type = try {
        request.contentType()
    } catch (e: BadContentTypeFormatException) {
        return false
    }
    return type.match(ContentType.Application.Json) || (type.contentType == "application" && type.contentSubtype.endsWith("+json"))
}

/** Fixed answers for unreadable request bodies (AOS-633): nothing from the input or the decoder. */
internal val MALFORMED_BODY = ApiError("VALIDATION_004", "Malformed request body")
internal val UNSUPPORTED_BODY = ApiError("VALIDATION_005", "Unsupported request body type")
internal val BODY_TOO_LARGE = ApiError("VALIDATION_006", "Request body too large")
