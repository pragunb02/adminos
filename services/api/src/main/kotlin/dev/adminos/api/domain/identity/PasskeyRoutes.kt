package dev.adminos.api.domain.identity

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.infrastructure.plugins.AUTH_PENDING
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.util.UUID

@Serializable
data class PasskeyResponse(val id: String, val name: String, val createdAt: String, val lastUsedAt: String?)

@Serializable
data class PasskeyStatusResponse(
    /** `enrol` (set up the first passkey), `verify` (use it) or `done`. */
    val step: String,
    val passkeys: List<PasskeyResponse>,
    val recoveryCodesLeft: Int
)

@Serializable
data class PasskeyOptionsResponse(
    /** The WebAuthn options for `navigator.credentials.create/get`, binary values as base64url (`{"publicKey":{…}}`). */
    val options: JsonElement
)

@Serializable
data class PasskeyFinishRequest(val response: JsonElement, val name: String? = null)

@Serializable
data class PasskeyRegisteredResponse(
    val passkey: PasskeyResponse,
    /** Shown once, only when they were (re)generated: write them down. */
    val recoveryCodes: List<String>,
    val step: String
)

@Serializable
data class RecoveryRequest(val code: String)

@Serializable
data class RecoveryResponse(val step: String, val recoveryCodesLeft: Int)

@Serializable
data class RecoveryCodesResponse(val recoveryCodes: List<String>)

private val log = LoggerFactory.getLogger("PasskeyRoutes")
private const val MAX_RESPONSE_CHARS = 64 * 1024

private fun Passkey.toResponse() = PasskeyResponse(id.toString(), name, createdAt.toString(), lastUsedAt?.toString())
private fun SecondFactorStep.wire() = name.lowercase()

/**
 * The passkey step after Google (AOS-617), under `/api/v1/auth/passkey`. These routes accept a session that hasn't
 * passed the second factor yet ([AUTH_PENDING]); every other route needs one that has.
 */
fun Route.passkeyRoutes(service: PasskeyService, config: AppConfig) {
    val cookies = AuthCookies(config)

    authenticate(AUTH_PENDING) {
        route("/auth/passkey") {
            get("/status") {
                val (user, session) = call.who()
                val s = mapped(call, cookies) { service.status(user, session) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(
                    PasskeyStatusResponse(s.step.wire(), s.passkeys.map { it.toResponse() }, s.recoveryCodesLeft), call.requestId))
            }
            post("/register/options") {
                val (user, session) = call.who()
                val options = mapped(call, cookies) { service.registrationOptions(user, session) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(PasskeyOptionsResponse(Json.parseToJsonElement(options)), call.requestId))
            }
            post("/register") {
                val (user, session) = call.who()
                val body = call.receive<PasskeyFinishRequest>()
                val enrolled = mapped(call, cookies) { service.register(user, session, responseJson(body), body.name.orEmpty()) }
                call.respond(HttpStatusCode.Created, ApiResponse.success(
                    PasskeyRegisteredResponse(enrolled.passkey.toResponse(), enrolled.recoveryCodes, enrolled.step.wire()), call.requestId))
            }
            post("/authenticate/options") {
                val (user, session) = call.who()
                val options = mapped(call, cookies) { service.authenticationOptions(user, session) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(PasskeyOptionsResponse(Json.parseToJsonElement(options)), call.requestId))
            }
            post("/authenticate") {
                val (user, session) = call.who()
                val body = call.receive<PasskeyFinishRequest>()
                mapped(call, cookies) { service.authenticate(user, session, responseJson(body)) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("step" to SecondFactorStep.DONE.wire()), call.requestId))
            }
            post("/recover") {
                val (user, session) = call.who()
                val body = call.receive<RecoveryRequest>()
                if (body.code.length > 64) throw ApiException(HttpStatusCode.BadRequest, ApiError("PASSKEY_005", "That recovery code isn't valid"))
                val left = mapped(call, cookies) { service.recover(user, session, body.code) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(RecoveryResponse(SecondFactorStep.DONE.wire(), left), call.requestId))
            }
            post("/recovery-codes") {
                val (user, session) = call.who()
                val codes = mapped(call, cookies) { service.regenerateRecoveryCodes(user, session) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(RecoveryCodesResponse(codes), call.requestId))
            }
        }
    }
}

/** The signed-in user and their session. A token without a session (dev-mode tokens) can't do the passkey step. */
private fun ApplicationCall.who(): Pair<UUID, UUID> {
    val p = userPrincipal
    val sid = p.sessionId ?: throw ApiException(HttpStatusCode.Unauthorized, ApiError.TOKEN_INVALID)
    return p.userId to sid
}

private fun responseJson(body: PasskeyFinishRequest): String {
    val json = body.response.toString()
    if (json.length > MAX_RESPONSE_CHARS) throw ApiException(HttpStatusCode.BadRequest, ApiError("PASSKEY_001", "The passkey wasn't accepted"))
    return json
}

/** Domain outcomes → HTTP. Verification details are logged, never returned (they help an attacker tune a forgery). */
private suspend fun <T> mapped(call: ApplicationCall, cookies: AuthCookies, work: suspend () -> T): T = try {
    work()
} catch (e: PasskeyException.Rejected) {
    log.warn("Passkey rejected for session {}: {}", call.userPrincipal.sessionId, e.message)
    throw ApiException(HttpStatusCode.BadRequest, ApiError("PASSKEY_001", "The passkey wasn't accepted. Try again."))
} catch (e: PasskeyException.NoCeremony) {
    throw ApiException(HttpStatusCode.Conflict, ApiError("PASSKEY_002", "This passkey request expired. Start again."))
} catch (e: PasskeyException.NotAllowed) {
    throw ApiException(HttpStatusCode.Forbidden, ApiError("PASSKEY_003", "Confirm with your passkey first."))
} catch (e: PasskeyException.NoneEnrolled) {
    throw ApiException(HttpStatusCode.Conflict, ApiError("PASSKEY_004", "No passkey is set up yet."))
} catch (e: PasskeyException.BadRecoveryCode) {
    throw ApiException(HttpStatusCode.BadRequest, ApiError("PASSKEY_005", "That recovery code isn't valid"))
} catch (e: PasskeyException.SignedOut) {
    cookies.clear(call)
    throw ApiException(HttpStatusCode.Unauthorized, ApiError.TOKEN_INVALID)
} catch (e: PasskeyException.TooManyAttempts) {
    cookies.clear(call)
    throw ApiException(HttpStatusCode.Unauthorized, ApiError("PASSKEY_006", "Too many failed attempts. Sign in again."))
}
