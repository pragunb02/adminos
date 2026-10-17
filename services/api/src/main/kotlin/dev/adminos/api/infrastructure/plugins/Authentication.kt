package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.identity.JwtService
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
    private val isActive: suspend (userId: UUID, sessionId: UUID) -> Boolean
) {
    suspend fun allows(userId: UUID, sessionId: UUID?): Boolean =
        if (sessionId == null) !requireSession else isActive(userId, sessionId)
}

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
    install(Authentication) {
        bearer("auth-jwt") {
            authHeader { call ->
                call.request.parseAuthorizationHeader()
                    ?: call.request.cookies[ACCESS_TOKEN_COOKIE]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { HttpAuthHeader.Single(AuthScheme.Bearer, it) }
            }
            authenticate { tokenCredential ->
                val claims = jwtService.verifyAccessToken(tokenCredential.token)
                if (claims != null && (sessions == null || sessions.allows(claims.userId, claims.sessionId))) {
                    UserPrincipal(
                        userId = claims.userId,
                        email = claims.email,
                        role = claims.role,
                        zone = claims.timezone?.let { java.time.ZoneId.of(it) } ?: DEFAULT_ZONE,
                        sessionId = claims.sessionId
                    )
                } else {
                    null
                }
            }
        }
    }
}

/** Extension to get the authenticated user from any route */
val ApplicationCall.userPrincipal: UserPrincipal
    get() = principal<UserPrincipal>()
        ?: throw ApiException(HttpStatusCode.Unauthorized, ApiError.TOKEN_INVALID)
