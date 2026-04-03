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
import java.time.LocalDate
import java.util.UUID

fun Route.billRoutes(billTrackingService: BillTrackingService) {
    authenticate("auth-jwt") {
        route("/bills") {

            // GET /api/v1/bills — list with status filter + date range
            get {
                val principal = call.userPrincipal
                val status = call.parameters["status"]?.let {
                    BillStatus.valueOf(it.uppercase())
                }
                val from = call.parameters["from"]?.let { LocalDate.parse(it) }
                val to = call.parameters["to"]?.let { LocalDate.parse(it) }
                val cursor = call.parameters["cursor"]
                val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)

                val bills = billTrackingService.getUserBills(
                    userId = principal.userId,
                    status = status,
                    from = from,
                    to = to,
                    cursor = cursor,
                    limit = limit + 1
                )

                val hasMore = bills.size > limit
                val page = if (hasMore) bills.take(limit) else bills
                val nextCursor = if (hasMore) page.lastOrNull()?.dueDate?.toString() else null
                val total = billTrackingService.countUserBills(principal.userId, status)

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.paginated(
                        data = page.map { BillResponse.from(it) },
                        pagination = Pagination(cursor = nextCursor, hasMore = hasMore, total = total),
                        requestId = call.requestId
                    )
                )
            }

            // GET /api/v1/bills/upcoming — bills due within 30 days
            get("/upcoming") {
                val principal = call.userPrincipal
                val bills = billTrackingService.getUpcomingBills(principal.userId)

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        bills.map { BillResponse.from(it) },
                        call.requestId
                    )
                )
            }

            // GET /api/v1/bills/:id
            get("/{id}") {
                val principal = call.userPrincipal
                val billId = call.parameters["id"]?.let { UUID.fromString(it) }
                    ?: throw ApiException(HttpStatusCode.BadRequest, ApiError.BILL_NOT_FOUND)

                val bill = billTrackingService.getBill(billId)
                    ?: throw ApiException(HttpStatusCode.NotFound, ApiError.BILL_NOT_FOUND)

                if (bill.userId != principal.userId) {
                    throw ApiException(HttpStatusCode.NotFound, ApiError.BILL_NOT_FOUND)
                }

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(BillResponse.from(bill), call.requestId)
                )
            }
        }
    }
}
