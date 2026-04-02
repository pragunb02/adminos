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
                    ApiError("VALIDATION_001", cause.message ?: "Invalid input"),
                    call.requestId
                )
            )
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
