package dev.adminos.api.domain.ingestion.fallback.templates

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
import java.time.Clock
import java.util.UUID

/** Review → formats to learn (AOS-706). Owner-scoped: another user's template is simply not found. */
class TemplateReviewService(private val templates: MessageTemplateRepository, private val clock: Clock = Clock.systemUTC()) {
    suspend fun proposed(userId: UUID): List<MessageTemplate> = templates.proposed(userId, MAX_LIST)

    /** From now on this format is read by code, before any model, straight into the intake (D-107). */
    suspend fun approve(userId: UUID, id: UUID): Boolean = templates.decide(userId, id, TemplateStatus.APPROVED, clock.instant())

    /** Never proposed again; its messages keep going to the AI reader (and Review). */
    suspend fun reject(userId: UUID, id: UUID): Boolean = templates.decide(userId, id, TemplateStatus.REJECTED, clock.instant())

    companion object {
        const val MAX_LIST = 50
    }
}

/** A format as Review shows it: the bank's wording with slots, never values. */
@Serializable
data class MessageTemplateResponse(
    val id: String,
    /** sms | whatsapp | gmail */
    val channel: String,
    val bankCode: String?,
    /** debit | credit */
    val direction: String,
    /** The masked shape, e.g. `Card XX<ACCT> charged Rs.<AMT> at <M> on <DATE>.` */
    val shape: String,
    /** Confirmed readings of this format. */
    val seen: Int
) {
    companion object {
        fun from(t: MessageTemplate) = MessageTemplateResponse(t.id.toString(), t.channel.key, t.bankCode, t.direction.name.lowercase(), t.shape.text, t.seen)
    }
}

@Serializable
data class TemplateDecisionResponse(val id: String, val status: String)

/**
 * - `GET /review/templates`: formats waiting for a decision;
 * - `POST /review/templates/{id}/approve`: read this format by code from now on;
 * - `POST /review/templates/{id}/reject`: never propose it again.
 */
fun Route.templateReviewRoutes(service: TemplateReviewService) {
    authenticate("auth-jwt") {
        route("/review/templates") {
            get { call.respond(HttpStatusCode.OK, ApiResponse.success(service.proposed(call.userPrincipal.userId).map(MessageTemplateResponse::from), call.requestId)) }
            post("/{id}/approve") { decide(call, "approved") { user, id -> service.approve(user, id) } }
            post("/{id}/reject") { decide(call, "rejected") { user, id -> service.reject(user, id) } }
        }
    }
}

private suspend fun decide(call: ApplicationCall, status: String, action: suspend (UUID, UUID) -> Boolean) {
    val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ApiException(HttpStatusCode.BadRequest, ApiError("VALIDATION_003", "Invalid id"))
    if (!action(call.userPrincipal.userId, id)) throw ApiException(HttpStatusCode.NotFound, ApiError("REVIEW_002", "Not found or already decided"))
    call.respond(HttpStatusCode.OK, ApiResponse.success(TemplateDecisionResponse(id.toString(), status), call.requestId))
}
