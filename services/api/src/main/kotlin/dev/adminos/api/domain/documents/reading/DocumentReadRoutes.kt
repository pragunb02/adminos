package dev.adminos.api.domain.documents.reading

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.documents.DocumentIntegrityException
import dev.adminos.api.domain.documents.DocumentNotFoundException
import dev.adminos.api.domain.documents.DocumentObjectMissingException
import dev.adminos.api.domain.documents.DocumentStorageException
import dev.adminos.api.domain.documents.DocumentStorageNotConfiguredException
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.RateLimiter
import dev.adminos.api.infrastructure.plugins.RateLimiters
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class FieldProposalResponse(val field: String, val value: String, val context: String, val label: String, val from: String)

@Serializable
data class FieldProposalsResponse(
    val source: String, val pagesRead: Int, val pagesTotal: Int, val truncated: Boolean,
    val proposals: List<FieldProposalResponse>, val ai: String
)

internal fun FieldProposals.toResponse() = FieldProposalsResponse(
    source = source.name.lowercase(), pagesRead = pagesRead, pagesTotal = pagesTotal, truncated = truncated,
    proposals = proposals.map { FieldProposalResponse(it.field.wire, it.value, it.context, it.label, it.from.name.lowercase()) },
    ai = ai.name.lowercase()
)

/**
 * `POST /api/v1/documents/{id}/read` (AOS-717): reads the owner's document (text layer or local OCR) and proposes its
 * expiry date, number (last 4 only), issuer and amount. Nothing is saved: the owner confirms with `PATCH /documents/{id}`.
 * - signed-in owner only; anyone else's id, or one that doesn't exist, is the same 404 (`DOC_001`);
 * - a POST (it is heavy work), so the app-wide cross-site check applies; no body;
 * - 10 reads a minute per user (`RATE_001`), and the parser itself runs at most 2 at once;
 * - a file the reader can't read is 422 `DOC_008` with the reader's code in `details`; the reader down is 503 `DOC_009`.
 */
fun Route.documentReadRoutes(service: DocumentFieldService, limiter: RateLimiter = RateLimiters.documentRead()) {
    authenticate("auth-jwt") {
        post("/documents/{id}/read") {
            val userId = call.userPrincipal.userId
            val key = "document-read:$userId"
            if (!limiter.isAllowed(key)) {
                throw ApiException(HttpStatusCode.TooManyRequests, ApiError("RATE_001", "Too many document readings. Try again in ${limiter.retryAfterSeconds(key)} seconds."))
            }
            val id = try { UUID.fromString(call.parameters["id"]) } catch (_: IllegalArgumentException) { throw notFound() }
            val result = try {
                service.propose(userId, id)
            } catch (_: DocumentNotFoundException) {
                throw notFound()
            } catch (e: DocumentReadException) {
                if (e.clientError) throw ApiException(HttpStatusCode.UnprocessableEntity, ApiError("DOC_008", e.message.orEmpty(),
                    kotlinx.serialization.json.JsonObject(mapOf("reason" to kotlinx.serialization.json.JsonPrimitive(e.code)))))
                throw ApiException(HttpStatusCode.ServiceUnavailable, ApiError("DOC_009", "The document reader isn't available; try again shortly"))
            } catch (_: DocumentStorageNotConfiguredException) {
                throw ApiException(HttpStatusCode.ServiceUnavailable, ApiError("DOC_005", "The documents vault isn't set up on this server yet"))
            } catch (_: DocumentObjectMissingException) {
                throw ApiException(HttpStatusCode.BadGateway, ApiError("DOC_004", "The stored file is missing"))
            } catch (_: DocumentStorageException) {
                throw ApiException(HttpStatusCode.ServiceUnavailable, ApiError("DOC_003", "Document storage is unavailable; try again shortly"))
            } catch (_: DocumentIntegrityException) {
                throw ApiException(HttpStatusCode.BadGateway, ApiError("DOC_004", "The stored file failed its integrity check"))
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK, ApiResponse.success(result.toResponse(), call.requestId))
        }
    }
}

private fun notFound() = ApiException(HttpStatusCode.NotFound, ApiError("DOC_001", "Document not found"))
