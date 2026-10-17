package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.SessionAccess
import io.ktor.http.*
import io.ktor.http.auth.AuthScheme
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

/**
 * Principal carrying verified JWT claims through the request pipeline.
 */
data class UserPrincipal(
    val userId: UUID,
    val email: String,
    val role: String,
    /** Day boundaries for this user (AOS-120): their stored timezone, or [DEFAULT_ZONE] for older tokens. */
    val zone: java.time.ZoneId = DEFAULT_ZONE,
    /** The session behind this request (AOS-620), when the token names one. */
    val sessionId: UUID? = null
) : Principal

/**
 * Per-request session check (AOS-620, AUD-04): an access token is only as alive as its session, so logout,
 * "sign out everywhere" and reuse detection end it at once instead of when it expires.
 * - A token with a `sid` must name an active session of its own user.
 * - A token without one is accepted only when [requireSession] is false (in-memory dev mode and route tests, where
 *   tokens are minted directly); with a database every token must carry a live session.
 */
class SessionGate(
    private val requireSession: Boolean,
    private val lookup: suspend (userId: UUID, sessionId: UUID) -> SessionAccess
) {
    suspend fun access(userId: UUID, sessionId: UUID?): SessionAccess =
        if (sessionId == null) (if (requireSession) SessionAccess.DENIED else SessionAccess.FULL) else lookup(userId, sessionId)
}

/** Provider for the passkey step itself: a live session that hasn't passed the second factor yet is enough (AOS-617). */
const val AUTH_PENDING = "auth-pending"

/** Set when a request was refused only because its session still needs the passkey (→ `AUTH_010`). */
internal val SecondFactorPending = io.ktor.util.AttributeKey<Boolean>("SecondFactorPending")

/** 401 for a signed-in session that still needs its passkey: the web app sends the user to that step (AOS-617). */
val SECOND_FACTOR_REQUIRED = ApiError("AUTH_010", "Confirm it's you with your passkey")

/** HttpOnly cookie the web app receives at login (see AuthRoutes). */
const val ACCESS_TOKEN_COOKIE = "access_token"

/**
 * JWT authentication (nimbus-jose-jwt via JwtService).
 *
 * Token sources, in order:
 * 1. `Authorization: Bearer <jwt>`, for API clients (mobile, scripts).
 * 2. The HttpOnly `access_token` cookie, for the web app (JS can't read it, so it can't send a header).
 *    CSRF is mitigated by the cookie's `SameSite=Strict`.
 */
fun Application.configureAuthentication(jwtService: JwtService, sessions: SessionGate? = null) {
    fun principal(claims: dev.adminos.api.domain.identity.JwtClaims) = UserPrincipal(
        userId = claims.userId,
        email = claims.email,
        role = claims.role,
        zone = claims.timezone?.let { java.time.ZoneId.of(it) } ?: DEFAULT_ZONE,
        sessionId = claims.sessionId
    )
    suspend fun access(claims: dev.adminos.api.domain.identity.JwtClaims) = sessions?.access(claims.userId, claims.sessionId) ?: SessionAccess.FULL
    val tokenSource: (ApplicationCall) -> HttpAuthHeader? = { call ->
        call.request.parseAuthorizationHeader()
            ?: call.request.cookies[ACCESS_TOKEN_COOKIE]
                ?.takeIf { it.isNotBlank() }
                ?.let { HttpAuthHeader.Single(AuthScheme.Bearer, it) }
    }
    install(Authentication) {
        // Every app route: a valid token whose session is live and has passed the second factor (AOS-617).
        bearer("auth-jwt") {
            authHeader(tokenSource)
            authenticate { tokenCredential ->
                val claims = jwtService.verifyAccessToken(tokenCredential.token) ?: return@authenticate null
                when (access(claims)) {
                    SessionAccess.FULL -> principal(claims)
                    SessionAccess.PENDING_SECOND_FACTOR -> { attributes.put(SecondFactorPending, true); null }
                    SessionAccess.DENIED -> null
                }
            }
        }
        // The passkey step: the same, but a session still waiting for its passkey is let in.
        bearer(AUTH_PENDING) {
            authHeader(tokenSource)
            authenticate { tokenCredential ->
                val claims = jwtService.verifyAccessToken(tokenCredential.token) ?: return@authenticate null
                if (access(claims) == SessionAccess.DENIED) null else principal(claims)
            }
        }
    }
}

/** Extension to get the authenticated user from any route */
val ApplicationCall.userPrincipal: UserPrincipal
    get() = principal<UserPrincipal>()
        ?: throw ApiException(HttpStatusCode.Unauthorized, ApiError.TOKEN_INVALID)
