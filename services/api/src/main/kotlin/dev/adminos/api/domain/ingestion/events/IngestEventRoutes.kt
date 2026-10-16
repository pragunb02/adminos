package dev.adminos.api.domain.ingestion.events

import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/** Wire format (camelCase, D-31). Amounts are decimal strings, never floats. */
@Serializable
data class IngestEventDto(
    val source: String,
    val sourceRef: String,
    val occurredAt: String,
    val direction: String,
    val amount: String,
    val currency: String = "INR",
    val merchantRaw: String? = null,
    val accountHint: String? = null,
    val paymentMethod: String? = null,
    val upiVpa: String? = null
)

@Serializable
data class IngestEventsRequest(val events: List<IngestEventDto>)

@Serializable
data class IngestEventsResponse(val accepted: Int, val duplicates: Int)

/** POST /api/v1/ingest/events: the common intake every source adapter posts to (D-21). */
fun Route.ingestEventRoutes(service: IngestEventService) {
    authenticate("auth-jwt") {
        post("/ingest/events") {
            val userId = call.userPrincipal.userId
            val request = call.receive<IngestEventsRequest>()
            val events = request.events.mapIndexed { i, dto -> dto.toDomain(userId, "events[$i]") }
            val result = service.ingest(events)
            call.respond(HttpStatusCode.Accepted, ApiResponse.success(IngestEventsResponse(result.accepted, result.duplicates), call.requestId))
        }
    }
}

/** API-layer mapping. Bad values throw IllegalArgumentException → 400 with the field named. */
internal fun IngestEventDto.toDomain(userId: UUID, at: String): IngestEvent {
    fun <E : Enum<E>> parse(values: Array<E>, raw: String, field: String): E =
        values.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException("$at.$field: '$raw' is not one of ${values.joinToString { it.name.lowercase() }}")
    return try {
        IngestEvent(
            userId = userId,
            source = parse(IngestSource.entries.toTypedArray(), source, "source"),
            sourceRef = sourceRef,
            occurredAt = try { OffsetDateTime.parse(occurredAt).toInstant() }
                catch (_: DateTimeParseException) { throw IllegalArgumentException("$at.occurredAt: expected ISO-8601 with offset, e.g. 2026-10-16T09:12:00+05:30") },
            direction = parse(IngestDirection.entries.toTypedArray(), direction, "direction"),
            amount = amount.toBigDecimalOrNull() ?: throw IllegalArgumentException("$at.amount: '$amount' is not a decimal string"),
            currency = currency,
            merchantRaw = merchantRaw,
            accountHint = accountHint,
            paymentMethod = paymentMethod?.let { parse(PaymentMethod.entries.toTypedArray(), it, "paymentMethod") },
            upiVpa = upiVpa
        )
    } catch (e: IllegalArgumentException) {
        if (e.message?.startsWith(at) == true) throw e
        throw IllegalArgumentException("$at: ${e.message}", e)
    }
}
