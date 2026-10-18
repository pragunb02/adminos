package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.identity.DEVICE_INGEST_SMS
import dev.adminos.api.domain.identity.devicePrincipal
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.requestId
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime

@Serializable
data class RawSmsDto(val sender: String, val text: String, val receivedAt: String)

@Serializable
data class RawSmsBatchRequest(val messages: List<RawSmsDto>)

@Serializable
data class RawSmsResult(val index: Int, val status: String, val reason: String? = null)

@Serializable
data class RawSmsBatchResponse(val accepted: Int, val duplicates: Int, val ignored: Int, val results: List<RawSmsResult>, val mandates: Int = 0,
    /** Messages that updated an account's latest balance (AOS-720); counted apart from their transaction status. */
    val balances: Int = 0)

private const val MAX_MESSAGES = 100
private const val MAX_TEXT = 2_000
private val lenientJson = Json { ignoreUnknownKeys = true }

/** Errors here never include the request body or any message text (D-33). */
private fun bad(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, ApiError("VALIDATION_005", message))

/**
 * POST /api/v1/ingest/sms/raw: raw bank SMS from a device (Mac Messages reader, later an iOS Shortcut).
 * Device token with `ingest:sms` only. Text is parsed in memory and dropped (D-33).
 */
fun Route.rawSmsRoutes(intake: SmsIntake) {
    authenticate(DEVICE_INGEST_SMS) {
        post("/ingest/sms/raw") {
            val userId = call.devicePrincipal.userId
            // Decode by hand: the framework's decode errors can quote input, which would leak SMS text.
            val request = try {
                lenientJson.decodeFromString(RawSmsBatchRequest.serializer(), call.receiveText())
            } catch (_: Exception) {
                bad("Body must be {\"messages\":[{\"sender\",\"text\",\"receivedAt\"}]}")
            }
            if (request.messages.isEmpty() || request.messages.size > MAX_MESSAGES) bad("messages must contain 1..$MAX_MESSAGES items")

            var balances = 0
            val results = request.messages.mapIndexed { i, m ->
                if (m.text.length > MAX_TEXT || m.sender.length > 32) return@mapIndexed RawSmsResult(i, "ignored", "too long")
                val receivedAt = runCatching { OffsetDateTime.parse(m.receivedAt).toInstant() }.getOrNull()
                    ?: runCatching { Instant.parse(m.receivedAt) }.getOrNull()
                    ?: return@mapIndexed RawSmsResult(i, "ignored", "receivedAt is not ISO-8601")
                val outcome = intake.receive(userId, SmsMessage(m.sender, m.text, receivedAt))
                if (outcome.balanceNoted) balances++
                when (val r = outcome.result) {
                    SmsIntakeResult.Accepted -> RawSmsResult(i, "accepted")
                    SmsIntakeResult.Duplicate -> RawSmsResult(i, "duplicate")
                    is SmsIntakeResult.Ignored -> RawSmsResult(i, "ignored", r.reason)
                    SmsIntakeResult.Mandate -> RawSmsResult(i, "mandate")
                }
            }
            call.respond(HttpStatusCode.Accepted, ApiResponse.success(
                RawSmsBatchResponse(
                    accepted = results.count { it.status == "accepted" },
                    duplicates = results.count { it.status == "duplicate" },
                    mandates = results.count { it.status == "mandate" },
                    ignored = results.count { it.status == "ignored" },
                    balances = balances,
                    results = results
                ), call.requestId))
        }
    }
}
