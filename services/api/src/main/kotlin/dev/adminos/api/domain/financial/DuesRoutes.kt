package dev.adminos.api.domain.financial

import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException

@Serializable
data class DueItemResponse(
    val kind: String, val id: String, val name: String, val amount: String, val minimumDue: String? = null,
    val date: String, val status: String, val detail: String, val trial: Boolean
)

@Serializable
data class DueTotalsResponse(val open: String, val paid: String, val renewals: String)

@Serializable
data class DuesMonthResponse(val month: String, val today: String, val items: List<DueItemResponse>, val overdue: List<DueItemResponse>, val totals: DueTotalsResponse)

private fun DueItem.toResponse() = DueItemResponse(kind.name.lowercase(), id.toString(), name, amount.toMoney(), minimumDue?.toMoney(),
    date.toString(), status, detail, trial)

/** `GET /api/v1/dues?month=YYYY-MM` — a month of bills and expected renewals (AOS-503); default: this month, the user's date. */
fun Route.duesRoutes(dues: DuesService) {
    authenticate("auth-jwt") {
        get("/dues") {
            val principal = call.userPrincipal
            val today = LocalDate.now(principal.zone)
            val month = call.request.queryParameters["month"]?.let(::parseMonth) ?: YearMonth.from(today)
            require(!month.isBefore(YearMonth.from(today).minusYears(5)) && !month.isAfter(YearMonth.from(today).plusYears(5))) {
                "month: within five years of today"
            }
            val m = dues.month(principal.userId, month, today)
            call.respond(HttpStatusCode.OK, ApiResponse.success(DuesMonthResponse(
                month = m.month.toString(), today = m.today.toString(),
                items = m.items.map { it.toResponse() }, overdue = m.overdue.map { it.toResponse() },
                totals = DueTotalsResponse(m.totals.open.toMoney(), m.totals.paid.toMoney(), m.totals.renewals.toMoney())
            ), call.requestId))
        }
    }
}

private fun parseMonth(raw: String): YearMonth = try {
    YearMonth.parse(raw.trim())
} catch (e: DateTimeParseException) {
    throw IllegalArgumentException("month: expected YYYY-MM, e.g. 2026-10")
}
