package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.RateLimiter
import dev.adminos.api.infrastructure.plugins.RateLimiters
import dev.adminos.api.infrastructure.plugins.UNSUPPORTED_BODY
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.math.BigDecimal

// ── DTOs (money is exact decimal strings, D-30) ──

/** `{"action":"confirm"}`, or `{"action":"correct","payer":…,"day":…,"amount":"…"}`. */
@Serializable data class SalaryChoiceRequest(val action: String, val payer: String? = null, val day: Int? = null, val amount: String? = null)

@Serializable data class DetectedSalaryDto(
    val payer: String?,
    val usualDay: Int,
    val typicalAmount: String,
    val lastAmount: String,
    val lastReceivedOn: String,
    val expectedOn: String,
    val credits: Int
)

@Serializable data class SalaryChoiceDto(
    val payer: String?,
    val day: Int,
    val amount: String,
    /** confirmed / corrected */
    val kind: String,
    val updatedAt: String
)

@Serializable data class SalaryChoiceResponse(
    /** What detection alone finds (without the owner's choice); null when it finds none. */
    val detected: DetectedSalaryDto?,
    /** Why none was found (lowercase [NoSalaryReason]); null when one was. */
    val detectedReason: String?,
    val choice: SalaryChoiceDto?
)

/**
 * The owner confirms or corrects the detected salary (AOS-722):
 * - `GET /forecast/salary`: what detection alone sees, and the stored choice;
 * - `PUT /forecast/salary` `{"action":"confirm"}` stores the detected payer, usual day and amount (409 `SALARY_001`
 *   when nothing is detected, `SALARY_002` when the detected payer's name can't be stored);
 *   `{"action":"correct","payer":"…"|null,"day":1..31,"amount":"85000.00"}` stores the owner's (400 `VALIDATION_001`).
 * The user is the session's only; JSON only (a form post is what another site could send without a preflight);
 * 20 changes a minute per user. Neither the payer nor amounts are logged.
 */
fun Route.salaryChoiceRoutes(service: SalaryChoiceService, limiter: RateLimiter = RateLimiters.salaryChoice()) {
    authenticate("auth-jwt") {
        route("/forecast/salary") {
            get {
                val principal = call.userPrincipal
                call.respond(HttpStatusCode.OK, ApiResponse.success(service.view(principal.userId, principal.zone).toResponse(), call.requestId))
            }
            put {
                val principal = call.userPrincipal
                val key = "salary-choice:${principal.userId}"
                if (!limiter.isAllowed(key)) {
                    throw ApiException(HttpStatusCode.TooManyRequests, ApiError("RATE_001", "Too many salary changes. Try again in ${limiter.retryAfterSeconds(key)} seconds."))
                }
                if (!call.sendsJson()) throw ApiException(HttpStatusCode.UnsupportedMediaType, UNSUPPORTED_BODY)
                val body = call.receive<SalaryChoiceRequest>()
                val outcome = when (body.action) {
                    "confirm" -> {
                        require(body.payer == null && body.day == null && body.amount == null) { "confirm keeps what was detected: send only the action" }
                        service.confirm(principal.userId, principal.zone)
                    }
                    "correct" -> service.correct(principal.userId, principal.zone, body.toCorrection())
                    else -> throw IllegalArgumentException("action: confirm or correct")
                }
                when (outcome) {
                    is SalaryChoiceOutcome.Saved -> call.respond(HttpStatusCode.OK, ApiResponse.success(outcome.view.toResponse(), call.requestId))
                    is SalaryChoiceOutcome.NothingToConfirm -> throw ApiException(HttpStatusCode.Conflict,
                        ApiError("SALARY_001", "No salary was detected to confirm (${outcome.reason.name.lowercase()}): correct it instead"))
                    SalaryChoiceOutcome.NotStorable -> throw ApiException(HttpStatusCode.Conflict,
                        ApiError("SALARY_002", "The detected payer's name can't be kept as it is: correct it instead"))
                }
            }
        }
    }
}

/** Only `application/json` is read; the content type is checked before the body is. */
private fun ApplicationCall.sendsJson(): Boolean =
    try { request.contentType().match(ContentType.Application.Json) } catch (_: BadContentTypeFormatException) { false }

/** Plain digits with up to two decimals: no sign, exponent, spaces or grouping ("1e5", "-1", "1,000" are refused). */
private val AMOUNT = Regex("\\d{1,10}(\\.\\d{1,2})?")

private fun SalaryChoiceRequest.toCorrection(): SalaryCorrection {
    val day = requireNotNull(day) { "day: the usual payday, 1 to 31" }
    val raw = requireNotNull(amount) { "amount: the usual salary, e.g. 85000 or 85000.50" }
    require(raw.matches(AMOUNT)) { "amount: a rupee amount like 85000 or 85000.50" }
    return SalaryCorrection(payer, day, BigDecimal(raw))
}

internal fun SalaryChoiceView.toResponse() = SalaryChoiceResponse(
    detected = (detected as? SalaryFinding.Found)?.salary?.toDto(),
    detectedReason = (detected as? SalaryFinding.NotFound)?.reason?.name?.lowercase(),
    choice = choice?.let { SalaryChoiceDto(it.payer, it.day, it.amount.toMoney(), it.kind.name.lowercase(), it.updatedAt.toString()) }
)

private fun DetectedSalary.toDto() = DetectedSalaryDto(
    payer = payer, usualDay = usualDay, typicalAmount = typicalAmount.toMoney(), lastAmount = lastAmount.toMoney(),
    lastReceivedOn = lastReceivedOn.toString(), expectedOn = expectedOn.toString(), credits = credits
)
