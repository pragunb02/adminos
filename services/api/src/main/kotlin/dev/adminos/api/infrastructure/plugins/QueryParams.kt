package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.domain.common.ApiError
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Typed, validated query-parameter parsing for routes (API layer).
 * Invalid values are a 400 with a clear message: never a 500 and never silently ignored.
 * Names are camelCase like the JSON contract (D-31).
 */

/** The zone for users without a stored timezone, and for access tokens issued before AOS-120. */
val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

/** The signed-in user's timezone (AOS-120): date-only params are whole days here. */
val ApplicationCall.userZone: ZoneId
    get() = principal<UserPrincipal>()?.zone ?: DEFAULT_ZONE

fun invalidParam(name: String, value: String, expected: String): Nothing =
    throw ApiException(HttpStatusCode.BadRequest, ApiError("VALIDATION_003", "Invalid '$name': '$value'. Expected $expected."))

inline fun <reified E : Enum<E>> ApplicationCall.enumParam(name: String): E? =
    parameters[name]?.let { raw ->
        enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: invalidParam(name, raw, "one of " + enumValues<E>().joinToString { it.name.lowercase() })
    }

fun ApplicationCall.booleanParam(name: String): Boolean? =
    parameters[name]?.let { raw -> raw.toBooleanStrictOrNull() ?: invalidParam(name, raw, "true or false") }

fun ApplicationCall.uuidParam(name: String): UUID? =
    parameters[name]?.let { raw -> runCatching { UUID.fromString(raw) }.getOrElse { invalidParam(name, raw, "a UUID") } }

fun ApplicationCall.limitParam(default: Int = 20, max: Int = 100): Int =
    parameters["limit"]?.let { raw -> raw.toIntOrNull()?.takeIf { it in 1..max } ?: invalidParam("limit", raw, "an integer 1..$max") }
        ?: default

/**
 * Start of a time range: an ISO instant (`2026-10-01T00:00:00Z`) or a date (`2026-10-01`, the start
 * of that day in the user's timezone, [userZone]).
 */
fun ApplicationCall.rangeStartParam(name: String): Instant? = parameters[name]?.let { parseInstantOrDate(name, it, endOfDay = false, userZone) }

/** End of a time range (inclusive): an ISO instant or a date (the end of that day in [userZone]). */
fun ApplicationCall.rangeEndParam(name: String): Instant? = parameters[name]?.let { parseInstantOrDate(name, it, endOfDay = true, userZone) }

fun ApplicationCall.dateParam(name: String): LocalDate? =
    parameters[name]?.let { raw ->
        try { LocalDate.parse(raw) } catch (_: DateTimeParseException) { invalidParam(name, raw, "a date like 2026-10-01") }
    }

private fun parseInstantOrDate(name: String, raw: String, endOfDay: Boolean, zone: ZoneId): Instant =
    runCatching { Instant.parse(raw) }.getOrNull()
        ?: runCatching { LocalDate.parse(raw) }.getOrNull()?.let { day ->
            if (endOfDay) day.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1)
            else day.atStartOfDay(zone).toInstant()
        }
        ?: invalidParam(name, raw, "a date (2026-10-01) or an ISO instant (2026-10-01T00:00:00Z)")
