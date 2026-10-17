package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

/**
 * Caps every request body **before** routing, authentication or decoding (AOS-615, finding AUD-01).
 *
 * Without it, an unauthenticated `POST /auth/google` with a 100 MB JSON body was read and decoded in full:
 * a handful of such requests could exhaust the heap. Authenticated routes were safe only by accident of order.
 *
 * Rules (fail closed):
 * - A body must declare its size: `Content-Length` is required. A chunked body (no length) gets **411**.
 *   Browsers, the Next proxy (verified: it keeps `Content-Length`), Pub/Sub and the SMS reader all send it.
 * - A declared size over the path's limit gets **413** without a byte of the body being read.
 * - The default limit is [DEFAULT_LIMIT] (JSON). Only the upload routes in [UPLOAD_PATHS] get [UPLOAD_LIMIT]: a new
 *   upload route must be added there on purpose (reviewed), it can't inherit a large limit silently.
 *
 * Route handlers still enforce their own exact file limits (20 MB) and multipart part limits.
 */
object RequestBodyLimits {
    /** Every JSON body we accept is far below this (the largest, a 100-message SMS batch, is ~250 KB). */
    const val DEFAULT_LIMIT: Long = 1L * 1024 * 1024

    /** A 20 MB file plus multipart framing and the small form fields that travel with it. */
    const val UPLOAD_LIMIT: Long = 21L * 1024 * 1024

    /** `POST` paths that take a file (multipart). Exact paths, no prefixes. */
    val UPLOAD_PATHS: Set<String> = setOf(
        "/api/v1/statements",
        "/api/v1/documents",
        "/api/v1/imports/whatsapp"
    )

    fun limitFor(method: HttpMethod, path: String): Long =
        if (method == HttpMethod.Post && path.trimEnd('/') in UPLOAD_PATHS) UPLOAD_LIMIT else DEFAULT_LIMIT

    internal val TOO_LARGE = ApiError("REQUEST_TOO_LARGE", "The request body is too large")
    internal val FILE_TOO_LARGE = ApiError("FILE_TOO_LARGE", "The file is too large (at most 20 MB)")
    internal val LENGTH_REQUIRED = ApiError("REQUEST_LENGTH_REQUIRED", "The request body must declare its length")
}

val RequestBodyLimit = createApplicationPlugin("RequestBodyLimit") {
    onCall { call ->
        val request = call.request
        val chunked = request.headers[HttpHeaders.TransferEncoding] != null
        val declared = request.headers[HttpHeaders.ContentLength]?.trim()
        if (chunked && declared == null) {
            call.response.header(HttpHeaders.Connection, "close")
            call.respond(HttpStatusCode.LengthRequired, ApiResponse.error(RequestBodyLimits.LENGTH_REQUIRED, call.requestId))
            return@onCall
        }
        if (declared == null) return@onCall // no body
        val size = declared.toLongOrNull()
        val path = request.path()
        val limit = RequestBodyLimits.limitFor(request.httpMethod, path)
        if (size == null || size < 0 || size > limit) {
            val error = if (limit == RequestBodyLimits.UPLOAD_LIMIT) RequestBodyLimits.FILE_TOO_LARGE else RequestBodyLimits.TOO_LARGE
            call.response.header(HttpHeaders.Connection, "close")
            call.respond(HttpStatusCode.PayloadTooLarge, ApiResponse.error(error, call.requestId))
        }
    }
}

fun Application.configureRequestBodyLimit() {
    install(RequestBodyLimit)
}
