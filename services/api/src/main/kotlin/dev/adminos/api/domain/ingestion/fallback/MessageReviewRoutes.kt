package dev.adminos.api.domain.ingestion.fallback

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
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

/** A message a model read, as Review shows it. Untrusted text ([merchant]) is shown as text, never as HTML. */
@Serializable
data class MessageProposalResponse(
    val id: String,
    /** sms | whatsapp | gmail */
    val channel: String,
    /** debit | credit */
    val direction: String,
    val amount: String,
    val accountLast4: String?,
    val merchant: String?,
    val bankCode: String?,
    val occurredAt: String,
    /** The model that read it, e.g. `qwen3:4b`. */
    val readBy: String
) {
    companion object {
        fun from(p: MessageProposal) = MessageProposalResponse(
            id = p.id.toString(), channel = p.channel.key, direction = p.direction.name.lowercase(), amount = p.amount.toPlainString(),
            accountLast4 = p.accountLast4, merchant = p.merchant, bankCode = p.bankCode, occurredAt = p.occurredAt.toString(), readBy = p.model
        )
    }
}

@Serializable
data class MessageDecisionResponse(val id: String, val status: String, val duplicate: Boolean = false)

/**
 * Review → messages read by AI (AOS-705, D-106). Owner-only (signed-in user); another user's proposal is 404.
 * - `GET /review/messages`: pending proposals, newest first (at most 100);
 * - `POST /review/messages/{id}/confirm`: becomes an intake event (then a transaction, through the pipeline);
 * - `POST /review/messages/{id}/dismiss`: kept out of the totals for good.
 */
fun Route.messageReviewRoutes(service: MessageReviewService) {
    authenticate("auth-jwt") {
        route("/review/messages") {
            get {
                val list = service.pending(call.userPrincipal.userId).map(MessageProposalResponse::from)
                call.respond(HttpStatusCode.OK, ApiResponse.success(list, call.requestId))
            }
            post("/{id}/confirm") {
                val id = proposalId(call)
                when (val r = service.confirm(call.userPrincipal.userId, id)) {
                    is ConfirmOutcome.Confirmed -> call.respond(HttpStatusCode.OK, ApiResponse.success(MessageDecisionResponse(id.toString(), "confirmed", r.duplicate), call.requestId))
                    ConfirmOutcome.NotFound -> notFound()
                }
            }
            post("/{id}/dismiss") {
                val id = proposalId(call)
                if (!service.dismiss(call.userPrincipal.userId, id)) notFound()
                call.respond(HttpStatusCode.OK, ApiResponse.success(MessageDecisionResponse(id.toString(), "dismissed"), call.requestId))
            }
        }
    }
}

private fun proposalId(call: ApplicationCall): UUID =
    call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ApiException(HttpStatusCode.BadRequest, ApiError("VALIDATION_003", "Invalid id"))

private fun notFound(): Nothing = throw ApiException(HttpStatusCode.NotFound, ApiError("REVIEW_001", "Not found or already decided"))
