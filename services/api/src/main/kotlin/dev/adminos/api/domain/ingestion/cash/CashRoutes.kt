package dev.adminos.api.domain.ingestion.cash

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.RateLimiter
import dev.adminos.api.infrastructure.plugins.RateLimiters
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

@Serializable
data class CashEntryRequest(
    val clientRef: String,
    /** Decimal string, e.g. "250" or "249.50" (exact, D-30). */
    val amount: String,
    val category: String,
    val note: String? = null,
    /** ISO-8601 with offset; defaults to now. */
    val occurredAt: String? = null,
    /** Or a day (YYYY-MM-DD) in the user's zone, e.g. from a text preview (AOS-718): today means now, an earlier day its noon. */
    val date: String? = null
)

@Serializable
data class CashEntryResponse(val saved: Boolean, val duplicate: Boolean)

@Serializable
data class CashParseRequest(val text: String)

@Serializable
data class DraftEntryResponse(
    val amount: String,
    val category: String,
    val note: String?,
    val date: String,
    /** The part of the (normalized) text this entry was read from. */
    val evidence: String,
    /** remembered / catalogue / keyword / pattern / none / fallback */
    val categoryFrom: String,
    /** category_unknown / date_assumed / quantity_assumed / old_date / no_note */
    val warnings: List<String>
)

@Serializable
data class UnparsedResponse(val text: String, val reason: String)

@Serializable
data class CashParseResponse(val today: String, val entries: List<DraftEntryResponse>, val unparsed: List<UnparsedResponse>)

/** POST /api/v1/transactions/cash — cash quick-add (AOS-204). 202: it appears once the pipeline runs (seconds). */
fun Route.cashRoutes(intake: CashIntake, clock: Clock = Clock.systemUTC()) {
    authenticate("auth-jwt") {
        post("/transactions/cash") {
            val request = call.receive<CashEntryRequest>()
            val zone = call.userPrincipal.zone
            val saved = intake.add(call.userPrincipal.userId, request.toEntry(zone, LocalDate.now(clock.withZone(zone))))
            call.respond(HttpStatusCode.Accepted, ApiResponse.success(CashEntryResponse(saved = saved, duplicate = !saved), call.requestId))
        }
    }
}

/**
 * POST /api/v1/transactions/cash/parse — reads free text into cash entries to preview (AOS-718). Nothing is saved
 * or logged: the user confirms each entry, which is then posted to `/transactions/cash` like any other.
 */
fun Route.cashPhraseRoutes(parser: CashPhraseParser, clock: Clock = Clock.systemUTC(), limiter: RateLimiter = RateLimiters.cashParse()) {
    authenticate("auth-jwt") {
        post("/transactions/cash/parse") {
            val key = "cash-parse:${call.userPrincipal.userId}"
            if (!limiter.isAllowed(key)) {
                throw ApiException(HttpStatusCode.TooManyRequests, ApiError("RATE_001", "Too many cash readings. Try again in ${limiter.retryAfterSeconds(key)} seconds."))
            }
            // JSON only: a form or text/plain post is what another site could send without a preflight (CSRF).
            if (!call.request.contentType().match(ContentType.Application.Json)) {
                throw ApiException(HttpStatusCode.UnsupportedMediaType, ApiError("VALIDATION_004", "Send the text as JSON: {\"text\": \"…\"}"))
            }
            val text = call.receive<CashParseRequest>().text
            require(text.isNotBlank()) { "text: type what you spent, e.g. \"200 chai and 150 auto\"" }
            require(text.length <= CashPhraseParser.MAX_TEXT) { "text: at most ${CashPhraseParser.MAX_TEXT} characters" }
            val principal = call.userPrincipal
            val today = LocalDate.now(clock.withZone(principal.zone))
            val parsed = parser.parse(text, PhraseContext(principal.userId, today))
            call.respond(HttpStatusCode.OK, ApiResponse.success(parsed.toResponse(today), call.requestId))
        }
    }
}

internal fun PhraseParse.toResponse(today: LocalDate) = CashParseResponse(
    today = today.toString(),
    entries = entries.map { e ->
        DraftEntryResponse(e.amount.toMoney(), e.category.name.lowercase(), e.note, e.date.toString(), e.evidence,
            e.categoryFrom.name.lowercase(), e.warnings.map { it.name.lowercase() }.sorted())
    },
    unparsed = unparsed.map { UnparsedResponse(it.text, it.reason.name.lowercase()) }
)

/** API-layer mapping. Bad values throw IllegalArgumentException → 400 with the field named. */
internal fun CashEntryRequest.toEntry(zone: ZoneId, today: LocalDate) = CashEntry(
    clientRef = clientRef,
    amount = amount.trim().toBigDecimalOrNull() ?: throw IllegalArgumentException("amount: '$amount' is not a decimal string"),
    category = TransactionCategory.entries.firstOrNull { it.name.equals(category, ignoreCase = true) }
        ?: throw IllegalArgumentException("category: '$category' is not one of ${TransactionCategory.entries.joinToString { it.name.lowercase() }}"),
    note = note,
    occurredAt = when {
        occurredAt != null && date != null -> throw IllegalArgumentException("date: give occurredAt or date, not both")
        occurredAt != null -> try { OffsetDateTime.parse(occurredAt).toInstant() }
            catch (_: DateTimeParseException) { throw IllegalArgumentException("occurredAt: expected ISO-8601 with offset, e.g. 2026-10-16T09:12:00+05:30") }
        date != null -> dayToInstant(date, zone, today)
        else -> null
    }
)

/** A day in the user's zone: today is now (null, the intake's clock); an earlier day is its noon; a later one is refused. */
private fun dayToInstant(raw: String, zone: ZoneId, today: LocalDate): java.time.Instant? {
    val day = try { LocalDate.parse(raw.trim()) } catch (_: DateTimeParseException) { throw IllegalArgumentException("date: expected YYYY-MM-DD, e.g. 2026-10-16") }
    require(!day.isAfter(today)) { "date: cash is logged once it's spent, so not after today ($today)" }
    return if (day == today) null else day.atTime(LocalTime.NOON).atZone(zone).toInstant()
}
