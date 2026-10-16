package dev.adminos.api.domain.financial.links

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.financial.TransactionResponse
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class DuplicateSuggestionResponse(
    val id: String,
    val score: Int,
    val reason: String,
    val kept: TransactionResponse,
    val duplicate: TransactionResponse
)

/** Review possible duplicates found across sources (AOS-207). */
fun Route.duplicateRoutes(service: DuplicateService) {
    authenticate("auth-jwt") {
        route("/transactions/duplicates") {
            get {
                val list = service.suggestions(call.userPrincipal.userId).map {
                    DuplicateSuggestionResponse(it.link.id.toString(), it.link.score, it.link.reason,
                        TransactionResponse.from(it.kept), TransactionResponse.from(it.duplicate))
                }
                call.respond(HttpStatusCode.OK, ApiResponse.success(list, call.requestId))
            }
            post("/{id}/confirm") { decide(call) { user, id -> service.confirm(user, id) } }
            post("/{id}/reject") { decide(call) { user, id -> service.reject(user, id) } }
        }
    }
}

private suspend fun decide(call: ApplicationCall, action: suspend (UUID, UUID) -> Boolean) {
    val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ApiException(HttpStatusCode.BadRequest, ApiError("VALIDATION_003", "Invalid suggestion id"))
    if (!action(call.userPrincipal.userId, id)) {
        throw ApiException(HttpStatusCode.NotFound, ApiError("DUPLICATE_001", "Suggestion not found or already decided"))
    }
    call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("id" to id.toString()), call.requestId))
}
