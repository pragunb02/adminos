package dev.adminos.api.domain.financial.links

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.financial.TransactionResponse
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.enumParam
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
data class SuggestionResponse(
    val id: String,
    /** duplicate | transfer | refund | reversal */
    val relation: String,
    val score: Int,
    val reason: String,
    val primary: TransactionResponse,
    val related: TransactionResponse
)

/** Review suggested relations between transactions (AOS-207/208/209). Owner-only. */
fun Route.suggestionRoutes(service: SuggestionService) {
    authenticate("auth-jwt") {
        route("/transactions/suggestions") {
            get {
                val relation = call.enumParam<LinkRelation>("relation")
                val list = service.suggestions(call.userPrincipal.userId, relation).map {
                    SuggestionResponse(it.link.id.toString(), it.link.relation.name.lowercase(), it.link.score, it.link.reason,
                        TransactionResponse.from(it.primary), TransactionResponse.from(it.related))
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
        throw ApiException(HttpStatusCode.NotFound, ApiError("SUGGESTION_001", "Suggestion not found or already decided"))
    }
    call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("id" to id.toString()), call.requestId))
}
