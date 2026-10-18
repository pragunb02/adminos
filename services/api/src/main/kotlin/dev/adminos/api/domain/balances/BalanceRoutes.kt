package dev.adminos.api.domain.balances

import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/** One account's latest balance. Money is an exact decimal string (D-30); negative = overdrawn. */
@Serializable data class AccountBalanceDto(
    /** "hdfc", "sbi"…, or null when no input named the bank. */
    val bank: String?,
    val last4: String,
    val amount: String,
    val observedAt: String,
    /** The day it was true, on the user's calendar. */
    val observedOn: String,
    /** sms / statement */
    val source: String,
    val ageDays: Long,
    /** fresh / aging / stale */
    val freshness: String,
    /** Counted in [BalancesResponse.total] and in "money left" (not stale). */
    val counted: Boolean
)

@Serializable data class BalancesResponse(
    val today: String,
    val accounts: List<AccountBalanceDto>,
    /** The sum of the counted accounts' balances. */
    val total: String,
    val countedAccounts: Int,
    /** Older than this many days is stale: shown, never counted. */
    val maxAgeDays: Long
)

/**
 * `GET /api/v1/balances` — the signed-in user's latest balance per bank account (AOS-720, D-170). The user is the
 * session's; nothing in the request names an account or a user, so there's nothing to aim at another user's data.
 */
fun Route.balanceRoutes(service: BalanceService) {
    authenticate("auth-jwt") {
        get("/balances") {
            val principal = call.userPrincipal
            val overview = service.overview(principal.userId, principal.zone)
            call.respond(HttpStatusCode.OK, ApiResponse.success(overview.toResponse(), call.requestId))
        }
    }
}

internal fun BalanceOverview.toResponse() = BalancesResponse(
    today = today.toString(),
    accounts = accounts.map { a ->
        AccountBalanceDto(a.account.bankCode, a.account.last4, a.reading.amount.toMoney(), a.reading.observedAt.toString(), a.observedOn.toString(),
            a.reading.source.key, a.ageDays, a.freshness.name.lowercase(), a.freshness.counted)
    },
    total = total.toMoney(), countedAccounts = counted.size, maxAgeDays = BalanceRules.MAX_AGE_DAYS
)
