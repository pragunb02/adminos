package dev.adminos.api.domain.ingestion.cash

import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

@Serializable
data class CashEntryRequest(
    val clientRef: String,
    /** Decimal string, e.g. "250" or "249.50" (exact, D-30). */
    val amount: String,
    val category: String,
    val note: String? = null,
    /** ISO-8601 with offset; defaults to now. */
    val occurredAt: String? = null
)

@Serializable
data class CashEntryResponse(val saved: Boolean, val duplicate: Boolean)

/** POST /api/v1/transactions/cash — cash quick-add (AOS-204). 202: it appears once the pipeline runs (seconds). */
fun Route.cashRoutes(intake: CashIntake) {
    authenticate("auth-jwt") {
        post("/transactions/cash") {
            val request = call.receive<CashEntryRequest>()
            val saved = intake.add(call.userPrincipal.userId, request.toEntry())
            call.respond(HttpStatusCode.Accepted, ApiResponse.success(CashEntryResponse(saved = saved, duplicate = !saved), call.requestId))
        }
    }
}

/** API-layer mapping. Bad values throw IllegalArgumentException → 400 with the field named. */
internal fun CashEntryRequest.toEntry() = CashEntry(
    clientRef = clientRef,
    amount = amount.trim().toBigDecimalOrNull() ?: throw IllegalArgumentException("amount: '$amount' is not a decimal string"),
    category = TransactionCategory.entries.firstOrNull { it.name.equals(category, ignoreCase = true) }
        ?: throw IllegalArgumentException("category: '$category' is not one of ${TransactionCategory.entries.joinToString { it.name.lowercase() }}"),
    note = note,
    occurredAt = occurredAt?.let {
        try { OffsetDateTime.parse(it).toInstant() }
        catch (_: DateTimeParseException) { throw IllegalArgumentException("occurredAt: expected ISO-8601 with offset, e.g. 2026-10-16T09:12:00+05:30") }
    }
)
