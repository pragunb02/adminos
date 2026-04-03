package dev.adminos.api.domain.financial

import dev.adminos.api.domain.audit.ActorType
import dev.adminos.api.domain.audit.AuditActions
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.Pagination
import dev.adminos.api.infrastructure.plugins.ApiException
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
    categorizationService: CategorizationService,
    auditService: AuditService
) {
    authenticate("auth-jwt") {
        route("/transactions") {

            // GET /api/v1/transactions — browse with filters + cursor pagination
            get {
                val principal = call.userPrincipal
                val from = call.parameters["from"]?.let { Instant.parse(it) }
                val to = call.parameters["to"]?.let { Instant.parse(it) }
                val category = call.parameters["category"]?.let {
                    TransactionCategory.valueOf(it.uppercase())
                }
                val type = call.parameters["type"]?.let {
                    TransactionType.valueOf(it.uppercase())
                }
                val accountId = call.parameters["account"]?.let { UUID.fromString(it) }
                val isRecurring = call.parameters["is_recurring"]?.toBooleanStrictOrNull()
                val merchantSearch = call.parameters["q"]
                val cursor = call.parameters["cursor"]
                val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)

                val transactions = transactionRepository.findByUserId(
                    userId = principal.userId,
                    from = from, to = to, category = category, type = type,
                    accountId = accountId, isRecurring = isRecurring,
                    merchantSearch = merchantSearch, cursor = cursor, limit = limit + 1
                )

                val hasMore = transactions.size > limit
                val page = if (hasMore) transactions.take(limit) else transactions
                val nextCursor = if (hasMore) page.lastOrNull()?.transactedAt?.toString() else null
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
                val from = call.parameters["from"]?.let { Instant.parse(it) }
                    ?: Instant.now().minusSeconds(30L * 24 * 3600)
                val to = call.parameters["to"]?.let { Instant.parse(it) }
                    ?: Instant.now()

                val byCategory = transactionRepository.spendingByCategory(principal.userId, from, to)
                val totalSpending = byCategory.values.fold(0.0) { acc, v -> acc + v.toDouble() }

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        TransactionSummaryResponse(
                            period = PeriodInfo(from = from.toString(), to = to.toString()),
                            totalSpending = totalSpending,
                            byCategory = byCategory.mapKeys { it.key.name.lowercase() }
                                .mapValues { it.value.toDouble() }
                        ),
                        call.requestId
                    )
                )
            }

            // PATCH /api/v1/transactions/:id — user category override
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

                val updated = transactionRepository.update(txn.copy(
                    category = newCategory,
                    subcategory = request.subcategory,
                    categorySource = "user"
                ))

                val afterState = JsonObject(mapOf(
                    "category" to JsonPrimitive(newCategory.name.lowercase()),
                    "subcategory" to JsonPrimitive(request.subcategory ?: ""),
                    "category_source" to JsonPrimitive("user")
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
