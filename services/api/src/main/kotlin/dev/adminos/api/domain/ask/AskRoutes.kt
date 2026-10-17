package dev.adminos.api.domain.ask

import dev.adminos.api.domain.analytics.PeriodDto
import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

// ── DTOs (camelCase, money as exact strings, D-30/D-31) ──

@Serializable data class AskRequest(val question: String)

@Serializable data class AskStatusResponse(val enabled: Boolean, val maxQuestionLength: Int)

@Serializable data class AskLineDto(val label: String, val spent: String, val payments: Int, val category: String?)

@Serializable data class AskPointDto(val start: String, val spent: String)

@Serializable data class AskResultDto(
    val spent: String?,
    val received: String?,
    val payments: Int?,
    val total: String?,
    val rows: List<AskLineDto>,
    val points: List<AskPointDto>
)

/** One tool call: what the model asked for (typed), the dates it covered, and what it returned or why it didn't run. */
@Serializable data class AskStepDto(
    val id: String,
    val tool: String,
    val period: PeriodDto?,
    val category: String?,
    val merchant: String?,
    val bucket: String?,
    val limit: Int?,
    val result: AskResultDto?,
    val skipped: String?
)

@Serializable data class AskComparisonDto(val later: String, val earlier: String, val difference: String, val changePct: String?)

@Serializable data class AskResponse(
    val answer: String,
    val source: String,
    val notice: String?,
    val declineReason: String?,
    val steps: List<AskStepDto>,
    val comparisons: List<AskComparisonDto>
)

/**
 * `GET /api/v1/ask` (is Ask on here) and `POST /api/v1/ask` (AOS-710). Both need the signed-in owner; the user
 * and zone come from the token only. Errors carry codes the web maps to calm messages; no question text or
 * amount is ever echoed in an error.
 */
fun Route.askRoutes(service: AskService) {
    authenticate("auth-jwt") {
        route("/ask") {
            get {
                call.respond(HttpStatusCode.OK, ApiResponse.success(AskStatusResponse(service.enabled, AskService.MAX_QUESTION), call.requestId))
            }
            post {
                val principal = call.userPrincipal
                val body = call.receive<AskRequest>()
                when (val outcome = service.ask(principal.userId, principal.zone, body.question)) {
                    is AskOutcome.Answered -> call.respond(HttpStatusCode.OK, ApiResponse.success(outcome.answer.dto(), call.requestId))
                    is AskOutcome.Rejected -> {
                        val why = outcome.why
                        if (why is AskRejection.RateLimited) call.response.header("Retry-After", why.retryAfter.toSeconds().coerceAtLeast(1).toString())
                        throw why.exception()
                    }
                }
            }
        }
    }
}

private fun AskRejection.exception(): ApiException = when (this) {
    AskRejection.Empty -> ApiException(HttpStatusCode.BadRequest, ApiError("ASK_001", "Type a question first."))
    is AskRejection.TooLong -> ApiException(HttpStatusCode.BadRequest, ApiError("ASK_002", "Keep the question under $max characters."))
    AskRejection.HiddenCharacters -> ApiException(HttpStatusCode.BadRequest, ApiError("ASK_003", "The question has hidden characters. Type it again."))
    AskRejection.FeatureOff -> ApiException(HttpStatusCode.Conflict, ApiError("ASK_004", "Ask isn't turned on for this server."))
    AskRejection.NoConsent -> ApiException(HttpStatusCode.Conflict, ApiError("ASK_005", "Turn on Ask in Settings first."))
    is AskRejection.RateLimited -> ApiException(HttpStatusCode.TooManyRequests, ApiError("RATE_001", "Too many questions. Try again in ${retryAfter.toMinutes().coerceAtLeast(1)} minutes.",
        JsonObject(mapOf("retryAfterSeconds" to JsonPrimitive(retryAfter.toSeconds().coerceAtLeast(1))))))
    AskRejection.CapReached -> ApiException(HttpStatusCode.ServiceUnavailable, ApiError("ASK_006", "This month's AI budget is used up. Ask will work again next month."))
    AskRejection.Unavailable -> ApiException(HttpStatusCode.ServiceUnavailable, ApiError("ASK_007", "Ask can't reach its model right now. Try again in a few minutes."))
}

private fun lower(e: Enum<*>?) = e?.name?.lowercase(Locale.ROOT)

internal fun AskAnswer.dto(): AskResponse {
    val idOf = steps.filter { it.result != null }.map { it.id }
    return AskResponse(
        answer = text, source = source.key, notice = notice, declineReason = declineReason?.key,
        steps = steps.map { it.dto() },
        comparisons = comparisons.map { AskComparisonDto(idOf[it.later], idOf[it.earlier], it.difference.toMoney(), it.changePct?.toPlainString()) }
    )
}

private fun AskStep.dto(): AskStepDto {
    val c = call
    return AskStepDto(
        id = id, tool = c.tool.key,
        period = result?.range?.let { PeriodDto(it.from.toString(), it.to.toString()) },
        category = lower(when (c) { is AskToolCall.CategorySpend -> c.category; is AskToolCall.TopMerchants -> c.category; is AskToolCall.SpendingTrend -> c.category; else -> null }),
        merchant = (c as? AskToolCall.MerchantSpend)?.merchant?.name,
        bucket = lower((c as? AskToolCall.SpendingTrend)?.bucket),
        limit = (c as? AskToolCall.TopMerchants)?.limit,
        result = result?.dto(),
        skipped = refused?.value
    )
}

private fun merchants(rows: List<MerchantLine>) = rows.map { AskLineDto(it.name, it.spent.toMoney(), it.payments, it.category.name.lowercase(Locale.ROOT)) }

private fun AskToolResult.dto(): AskResultDto = when (this) {
    is AskToolResult.Summary -> AskResultDto(spent.toMoney(), received.toMoney(), payments, spent.toMoney(), emptyList(), emptyList())
    is AskToolResult.Categories -> AskResultDto(null, null, null, total.toMoney(), rows.map { AskLineDto(it.category.name.lowercase(Locale.ROOT), it.spent.toMoney(), it.payments, null) }, emptyList())
    is AskToolResult.Merchant -> AskResultDto(null, null, null, total.toMoney(), merchants(rows), emptyList())
    is AskToolResult.TopMerchants -> AskResultDto(null, null, null, null, merchants(rows), emptyList())
    is AskToolResult.Trend -> AskResultDto(null, null, null, total.toMoney(), emptyList(), points.map { AskPointDto(it.start.toString(), it.spent.toMoney()) })
}
