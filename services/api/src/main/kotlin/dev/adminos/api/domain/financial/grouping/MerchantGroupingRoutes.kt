package dev.adminos.api.domain.financial.grouping

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

@Serializable
data class MergeMemberResponse(val name: String, val payments: Int)

@Serializable
data class MergeProposalResponse(
    val id: String,
    /** The name the merge keeps. */
    val name: String,
    /** Every name in the group, the kept one first, each with how many payments it has. */
    val names: List<MergeMemberResponse>,
    /** Why they look like one merchant, in plain words. */
    val reasons: List<String>,
    val score: Double
) {
    companion object {
        fun from(p: MergeProposal) = MergeProposalResponse(
            id = p.id.toString(),
            name = p.canonical.name,
            names = (listOf(p.canonical) + p.members).map { MergeMemberResponse(it.name, it.payments) },
            reasons = p.evidence.sortedBy { it.ordinal }.map { it.text },
            score = p.score
        )
    }
}

@Serializable
data class MergeDecisionResponse(val id: String, val merged: Boolean, val name: String)

/** "Are these one merchant?" in Review (AOS-709). Owner-only; the rules are in [MerchantGroupingService]. */
fun Route.merchantGroupingRoutes(service: MerchantGroupingService) {
    authenticate("auth-jwt") {
        route("/merchants/merge-proposals") {
            get {
                val list = service.proposals(call.userPrincipal.userId).map(MergeProposalResponse::from)
                call.respond(HttpStatusCode.OK, ApiResponse.success(list, call.requestId))
            }
            post("/{id}/confirm") { decide(call) { user, id -> service.confirm(user, id) } }
            post("/{id}/reject") { decide(call) { user, id -> service.reject(user, id) } }
        }
    }
}

private suspend fun decide(call: ApplicationCall, action: suspend (UUID, UUID) -> MergeDecision) {
    val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ApiException(HttpStatusCode.BadRequest, ApiError("VALIDATION_003", "Invalid proposal id"))
    val body = when (val d = action(call.userPrincipal.userId, id)) {
        is MergeDecision.Merged -> MergeDecisionResponse(id.toString(), merged = true, name = d.proposal.canonical.name)
        is MergeDecision.Kept -> MergeDecisionResponse(id.toString(), merged = false, name = d.proposal.canonical.name)
        MergeDecision.NotFound -> throw ApiException(HttpStatusCode.NotFound, ApiError("MERCHANT_MERGE_001", "Proposal not found or already decided"))
    }
    call.respond(HttpStatusCode.OK, ApiResponse.success(body, call.requestId))
}
