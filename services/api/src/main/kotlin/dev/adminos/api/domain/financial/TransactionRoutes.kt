package dev.adminos.api.domain.financial

import dev.adminos.api.domain.audit.ActorType
import dev.adminos.api.domain.audit.AuditActions
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.CursorCodec
import dev.adminos.api.domain.common.Pagination
import dev.adminos.api.domain.common.sumMoney
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.infrastructure.plugins.*
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

fun Route.transactionRoutes(
    transactionRepository: TransactionRepository,
    corrections: CategoryCorrectionService,
    auditService: AuditService
) {
    authenticate("auth-jwt") {
        route("/transactions") {

            // GET /api/v1/transactions — browse with filters + cursor pagination
            get {
                val principal = call.userPrincipal
                val from = call.rangeStartParam("from")
                val to = call.rangeEndParam("to")
                val category = call.enumParam<TransactionCategory>("category")
                val type = call.enumParam<TransactionType>("type")
                val accountId = call.uuidParam("account")
                val isRecurring = call.booleanParam("isRecurring")
                val merchantSearch = call.parameters["merchant"]?.takeIf { it.isNotBlank() }
                val cursor = call.parameters["cursor"]
                val limit = call.limitParam()

                val transactions = transactionRepository.findByUserId(
                    userId = principal.userId,
                    from = from, to = to, category = category, type = type,
                    accountId = accountId, isRecurring = isRecurring,
                    merchantSearch = merchantSearch, cursor = cursor, limit = limit + 1
                )

                val hasMore = transactions.size > limit
                val page = if (hasMore) transactions.take(limit) else transactions
                val nextCursor = if (hasMore) page.lastOrNull()?.let { CursorCodec.encode(it.transactedAt.toString()) } else null
                val total = transactionRepository.countByUserId(
                    userId = principal.userId,
                    from = from, to = to, category = category, type = type,
                    accountId = accountId, isRecurring = isRecurring,
                    merchantSearch = merchantSearch
                )

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.paginated(
                        data = page.map { TransactionResponse.from(it) },
                        pagination = Pagination(cursor = nextCursor, hasMore = hasMore, total = total),
                        requestId = call.requestId
                    )
                )
            }

            // GET /api/v1/transactions/summary — spending by category
            get("/summary") {
                val principal = call.userPrincipal
                val from = call.rangeStartParam("from") ?: Instant.now().minusSeconds(30L * 24 * 3600)
                val to = call.rangeEndParam("to") ?: Instant.now()

                val byCategory = transactionRepository.spendingByCategory(principal.userId, from, to)
                val totalSpending = byCategory.values.sumMoney()

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        TransactionSummaryResponse(
                            period = PeriodInfo(from = from.toString(), to = to.toString()),
                            totalSpending = totalSpending.toMoney(),
                            byCategory = byCategory.mapKeys { it.key.name.lowercase() }
                                .mapValues { it.value.toMoney() }
                        ),
                        call.requestId
                    )
                )
            }

            // PATCH /api/v1/transactions/:id — user category override, remembered for the payee (AOS-210)
            patch("/{id}") {
                val principal = call.userPrincipal
                val txnId = call.parameters["id"]?.let { UUID.fromString(it) }
                    ?: throw ApiException(HttpStatusCode.BadRequest, ApiError.TRANSACTION_NOT_FOUND)

                val txn = transactionRepository.findById(txnId)
                    ?: throw ApiException(HttpStatusCode.NotFound, ApiError.TRANSACTION_NOT_FOUND)

                if (txn.userId != principal.userId) {
                    throw ApiException(HttpStatusCode.NotFound, ApiError.TRANSACTION_NOT_FOUND)
                }

                val request = call.receive<CategoryOverrideRequest>()
                val newCategory = TransactionCategory.valueOf(request.category.uppercase())

                val beforeState = JsonObject(mapOf(
                    "category" to JsonPrimitive(txn.category.name.lowercase()),
                    "subcategory" to JsonPrimitive(txn.subcategory ?: ""),
                    "category_source" to JsonPrimitive(txn.categorySource)
                ))

                val correction = corrections.correct(txn, newCategory, request.subcategory, request.remember)
                val updated = correction.transaction

                val afterState = JsonObject(mapOf(
                    "category" to JsonPrimitive(newCategory.name.lowercase()),
                    "subcategory" to JsonPrimitive(request.subcategory ?: ""),
                    "category_source" to JsonPrimitive("user"),
                    "remembered" to JsonPrimitive(correction.remembered),
                    "relabelled" to JsonPrimitive(correction.relabelled)
                ))

                auditService.log(
                    userId = principal.userId,
                    actor = ActorType.USER,
                    action = AuditActions.TRANSACTION_UPDATE_CATEGORY,
                    entityType = "transaction",
                    entityId = txnId,
                    beforeState = beforeState,
                    afterState = afterState
                )

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(TransactionResponse.from(updated), call.requestId)
                )
            }
        }
    }
}
