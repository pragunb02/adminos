package dev.adminos.api.domain.financial

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.Pagination
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.math.BigDecimal
import java.util.UUID

fun Route.subscriptionRoutes(subscriptionRepository: SubscriptionRepository) {
    authenticate("auth-jwt") {
        route("/subscriptions") {

            // GET /api/v1/subscriptions — list with status filter + cursor pagination
            get {
                val principal = call.userPrincipal
                val status = call.parameters["status"]?.let {
                    SubscriptionStatus.valueOf(it.uppercase())
                }
                val cursor = call.parameters["cursor"]
                val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)

                val subs = subscriptionRepository.findByUserId(
                    userId = principal.userId,
                    status = status,
                    cursor = cursor,
                    limit = limit + 1
                )

                val hasMore = subs.size > limit
                val page = if (hasMore) subs.take(limit) else subs
                val nextCursor = if (hasMore) page.lastOrNull()?.createdAt?.toString() else null
                val total = subscriptionRepository.countByUserId(principal.userId, status)

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.paginated(
                        data = page.map { SubscriptionResponse.from(it) },
                        pagination = Pagination(cursor = nextCursor, hasMore = hasMore, total = total),
                        requestId = call.requestId
                    )
                )
            }

            // GET /api/v1/subscriptions/summary
            get("/summary") {
                val principal = call.userPrincipal
                val allSubs = subscriptionRepository.findByUserId(principal.userId, limit = 1000)

                val activeCount = subscriptionRepository.countByUserId(principal.userId, SubscriptionStatus.ACTIVE)
                val cancelledCount = subscriptionRepository.countByUserId(principal.userId, SubscriptionStatus.CANCELLED)
                val flaggedCount = allSubs.count { it.isFlagged }.toLong()

                val activeSubs = allSubs.filter { it.status == SubscriptionStatus.ACTIVE }
                val totalMonthlyCost = activeSubs.fold(BigDecimal.ZERO) { acc, sub ->
                    acc + toMonthlyCost(sub.amount, sub.billingCycle)
                }

                val byCategory = activeSubs.groupBy { it.category }
                    .mapKeys { it.key.name.lowercase() }
                    .mapValues { (_, subs) ->
                        CategoryBreakdown(
                            count = subs.size,
                            monthly = subs.fold(0.0) { acc, s ->
                                acc + toMonthlyCost(s.amount, s.billingCycle).toDouble()
                            }
                        )
                    }

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        SubscriptionSummaryResponse(
                            totalMonthlyCost = totalMonthlyCost.toDouble(),
                            activeCount = activeCount,
                            flaggedCount = flaggedCount,
                            cancelledCount = cancelledCount,
                            byCategory = byCategory
                        ),
                        call.requestId
                    )
                )
            }

            // GET /api/v1/subscriptions/:id
            get("/{id}") {
                val principal = call.userPrincipal
                val subId = call.parameters["id"]?.let { UUID.fromString(it) }
                    ?: throw ApiException(HttpStatusCode.BadRequest, ApiError.SUBSCRIPTION_NOT_FOUND)

                val sub = subscriptionRepository.findById(subId)
                    ?: throw ApiException(HttpStatusCode.NotFound, ApiError.SUBSCRIPTION_NOT_FOUND)

                if (sub.userId != principal.userId) {
                    throw ApiException(HttpStatusCode.NotFound, ApiError.SUBSCRIPTION_NOT_FOUND)
                }

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(SubscriptionResponse.from(sub), call.requestId)
                )
            }
        }
    }
}

private fun toMonthlyCost(amount: BigDecimal, cycle: BillingCycle): BigDecimal = when (cycle) {
    BillingCycle.DAILY -> amount.multiply(BigDecimal(30))
    BillingCycle.WEEKLY -> amount.multiply(BigDecimal("4.33"))
    BillingCycle.MONTHLY -> amount
    BillingCycle.QUARTERLY -> amount.divide(BigDecimal(3), 2, java.math.RoundingMode.HALF_UP)
    BillingCycle.YEARLY -> amount.divide(BigDecimal(12), 2, java.math.RoundingMode.HALF_UP)
}
