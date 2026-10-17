package dev.adminos.api.domain.coach.limits

import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.coach.lenses.LENS_NOT_FOUND
import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import dev.adminos.api.infrastructure.plugins.userZone
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.util.UUID

@Serializable data class ScopeDto(val kind: String, val value: String)
@Serializable data class LimitStatusDto(val periodFrom: String, val periodTo: String, val spent: String, val used: String, val left: String, val reached: List<Int>, val daysLeft: Long)
@Serializable data class LimitDto(val id: String, val scope: ScopeDto, val label: String, val period: String, val amount: String, val status: LimitStatusDto)
@Serializable data class WeekSpendDto(val from: String, val to: String, val spent: String)
@Serializable data class SuggestionDto(
    val lens: String, val label: String, val period: String, val amount: String,
    val weeks: List<WeekSpendDto>, val median: String, val stepPercent: Int, val roundTo: String
)
@Serializable data class LearningDto(val daysOfData: Int, val daysNeeded: Int, val ready: Boolean, val label: String)
@Serializable data class LimitsResponse(val today: String, val learning: LearningDto, val limits: List<LimitDto>, val suggestions: List<SuggestionDto>)
@Serializable data class CreateLimitRequest(val scope: ScopeDto, val period: String, val amount: String)
@Serializable data class UpdateLimitRequest(val amount: String? = null, val period: String? = null)

val LIMIT_NOT_FOUND = ApiError("COACH_002", "Limit not found")
val LIMIT_EXISTS = ApiError("COACH_003", "That limit already exists")

internal fun LearningState.dto() = LearningDto(daysOfData, LearningState.DAYS_NEEDED, ready, label)

internal fun LimitStatus.dto() = LimitDto(
    id = limit.id.toString(), scope = ScopeDto(limit.scope.kind.name.lowercase(), limit.scope.value), label = limit.label,
    period = limit.period.name.lowercase(), amount = limit.amount.toMoney(),
    status = LimitStatusDto(period.from.toString(), period.to.toString(), spent.toMoney(), used.toPlainString(), left.toMoney(), reached.map { it.percent }, daysLeft)
)

internal fun LimitSuggestion.dto() = SuggestionDto(
    lens = lens.id.value, label = lens.label, period = period.name.lowercase(), amount = amount.toMoney(),
    weeks = weeks.map { WeekSpendDto(it.week.from.toString(), it.week.to.toString(), it.spent.toMoney()) },
    median = median.toMoney(), stepPercent = stepPercent, roundTo = roundTo.toMoney()
)

/**
 * Limits (AOS-904):
 * - `GET /coach/limits`: learning state, the owner's limits with today's status, and suggestions;
 * - `POST /coach/limits` `{scope:{kind,value}, period, amount}`: 201; 409 while learning or for a duplicate;
 * - `PATCH /coach/limits/{id}` `{amount?, period?}`, `DELETE /coach/limits/{id}`: 404 for anyone else's;
 * - `POST /coach/limits/suggestions/{lens}/dismiss`: "Not now".
 */
fun Route.limitRoutes(service: LimitService) {
    authenticate("auth-jwt") {
        route("/coach/limits") {
            get {
                val o = service.overview(call.userPrincipal.userId, call.userZone)
                call.respond(HttpStatusCode.OK, ApiResponse.success(
                    LimitsResponse(o.today.toString(), o.learning.dto(), o.statuses.map { it.dto() }, o.suggestions.map { it.dto() }), call.requestId))
            }
            post {
                val body = call.receive<CreateLimitRequest>()
                val request = LimitRequest(scopeOf(body.scope), periodOf(body.period), amountOf(body.amount))
                val out = service.create(call.userPrincipal.userId, call.userZone, request)
                call.respondOutcome(out, service, HttpStatusCode.Created)
            }
            patch("/{id}") {
                val id = call.limitId()
                val body = call.receive<UpdateLimitRequest>()
                require(body.amount != null || body.period != null) { "amount or period: give at least one" }
                val out = service.update(call.userPrincipal.userId, call.userZone, id, body.amount?.let(::amountOf), body.period?.let(::periodOf))
                call.respondOutcome(out, service, HttpStatusCode.OK)
            }
            delete("/{id}") {
                if (!service.delete(call.userPrincipal.userId, call.limitId())) throw ApiException(HttpStatusCode.NotFound, LIMIT_NOT_FOUND)
                call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("deleted" to true), call.requestId))
            }
            post("/suggestions/{lens}/dismiss") {
                if (!service.dismiss(call.userPrincipal.userId, call.parameters["lens"].orEmpty())) throw ApiException(HttpStatusCode.NotFound, LENS_NOT_FOUND)
                call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("dismissed" to true), call.requestId))
            }
        }
    }
}

private suspend fun ApplicationCall.respondOutcome(out: LimitOutcome, service: LimitService, ok: HttpStatusCode) {
    when (out) {
        is LimitOutcome.Saved -> {
            val status = service.statuses(userPrincipal.userId, userZone, listOf(out.limit)).single()
            respond(ok, ApiResponse.success(status.dto(), requestId))
        }
        is LimitOutcome.StillLearning -> throw ApiException(HttpStatusCode.Conflict, ApiError("COACH_004", "${out.learning.label}: limits open after ${LearningState.DAYS_NEEDED} days of data"))
        is LimitOutcome.Duplicate -> throw ApiException(HttpStatusCode.Conflict, LIMIT_EXISTS.copy(message = out.message))
        is LimitOutcome.UnknownLens -> throw ApiException(HttpStatusCode.NotFound, LENS_NOT_FOUND)
        LimitOutcome.NotFound -> throw ApiException(HttpStatusCode.NotFound, LIMIT_NOT_FOUND)
    }
}

private fun ApplicationCall.limitId(): UUID = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    ?: throw ApiException(HttpStatusCode.NotFound, LIMIT_NOT_FOUND)

private fun scopeOf(s: ScopeDto): LimitScope = when (s.kind.lowercase()) {
    "lens" -> LimitScope.Lens(LensId.parse(s.value) ?: throw IllegalArgumentException("scope.value: not a lens id"))
    "merchant" -> LimitScope.Merchant(s.value)
    "category" -> LimitScope.Category(TransactionCategory.entries.firstOrNull { it.name.equals(s.value, ignoreCase = true) }
        ?: throw IllegalArgumentException("scope.value: one of " + TransactionCategory.entries.filter { it != TransactionCategory.TRANSFER }.joinToString { it.name.lowercase() }))
    else -> throw IllegalArgumentException("scope.kind: one of lens, merchant, category")
}

private fun periodOf(raw: String): LimitPeriod =
    LimitPeriod.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: throw IllegalArgumentException("period: weekly or monthly")

private val AMOUNT = Regex("\\d{1,8}(\\.\\d{1,2})?")

private fun amountOf(raw: String): BigDecimal {
    require(raw.matches(AMOUNT)) { "amount: a rupee amount like 2000 or 1999.50" }
    return BigDecimal(raw)
}
