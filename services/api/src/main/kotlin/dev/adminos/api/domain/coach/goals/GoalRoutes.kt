package dev.adminos.api.domain.coach.goals

import dev.adminos.api.domain.coach.lenses.LENS_NOT_FOUND
import dev.adminos.api.domain.coach.limits.LimitPeriod
import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.toMoney
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

@Serializable data class GoalPeriodDto(val from: String, val to: String, val spent: String, val target: String, val met: Boolean, val current: Boolean)
@Serializable data class GoalDto(
    val id: String, val lens: String, val label: String, val kind: String, val value: String, val period: String,
    val baseline: String?, val target: String, val startedOn: String,
    val periods: List<GoalPeriodDto>, val metCount: Int, val completedCount: Int, val streak: Int, val bestStreak: Int
)
@Serializable data class CreateGoalRequest(val lens: String, val kind: String, val value: String, val period: String = "weekly")

val GOAL_NOT_FOUND = ApiError("COACH_005", "Goal not found")

internal fun GoalProgress.dto() = GoalDto(
    id = goal.id.toString(), lens = goal.lens.value, label = label, kind = goal.kind.name.lowercase(), value = goal.value.toPlainString(),
    period = goal.period.name.lowercase(), baseline = goal.baseline?.toMoney(), target = goal.target.toMoney(), startedOn = goal.startedOn.toString(),
    periods = periods.map { GoalPeriodDto(it.range.from.toString(), it.range.to.toString(), it.spent.toMoney(), it.target.toMoney(), it.met, it.current) },
    metCount = metCount, completedCount = completedCount, streak = streak.current, bestStreak = streak.best
)

/**
 * Goals (AOS-907): `GET /coach/goals`, `POST /coach/goals` `{lens, kind: cut_percent|cap_amount, value, period}`,
 * `DELETE /coach/goals/{id}` (404 for anyone else's).
 */
fun Route.goalRoutes(service: GoalService) {
    authenticate("auth-jwt") {
        route("/coach/goals") {
            get { call.respond(HttpStatusCode.OK, ApiResponse.success(service.list(call.userPrincipal.userId, call.userZone).map { it.dto() }, call.requestId)) }
            post {
                val body = call.receive<CreateGoalRequest>()
                val kind = GoalKind.entries.firstOrNull { it.name.equals(body.kind, ignoreCase = true) } ?: throw IllegalArgumentException("kind: cut_percent or cap_amount")
                val period = LimitPeriod.entries.firstOrNull { it.name.equals(body.period, ignoreCase = true) } ?: throw IllegalArgumentException("period: weekly or monthly")
                require(body.value.matches(VALUE)) { "value: a number like 30 or 4000" }
                if (kind == GoalKind.CUT_PERCENT) require(body.value.toIntOrNull() in 1..90) { "value: a cut is 1..90 percent" }
                when (val out = service.create(call.userPrincipal.userId, call.userZone, GoalRequest(body.lens, kind, BigDecimal(body.value), period))) {
                    is GoalOutcome.Saved -> call.respond(HttpStatusCode.Created, ApiResponse.success(out.progress.dto(), call.requestId))
                    is GoalOutcome.UnknownLens -> throw ApiException(HttpStatusCode.NotFound, LENS_NOT_FOUND)
                    is GoalOutcome.NoBaseline -> throw ApiException(HttpStatusCode.Conflict,
                        ApiError("COACH_006", "Nothing spent on this habit from ${out.months.from} to ${out.months.to}, so there is nothing to cut; set a cap instead"))
                    is GoalOutcome.Duplicate -> throw ApiException(HttpStatusCode.Conflict, ApiError("COACH_007", out.message))
                }
            }
            delete("/{id}") {
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw ApiException(HttpStatusCode.NotFound, GOAL_NOT_FOUND)
                if (!service.delete(call.userPrincipal.userId, id)) throw ApiException(HttpStatusCode.NotFound, GOAL_NOT_FOUND)
                call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("deleted" to true), call.requestId))
            }
        }
    }
}

private val VALUE = Regex("\\d{1,8}(\\.\\d{1,2})?")
