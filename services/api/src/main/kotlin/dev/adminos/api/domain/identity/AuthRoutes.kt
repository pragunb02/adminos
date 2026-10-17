package dev.adminos.api.domain.identity

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.ActorType
import dev.adminos.api.domain.audit.AuditActions
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.clientIp
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import dev.adminos.api.infrastructure.plugins.RateLimitPlugin
import dev.adminos.api.infrastructure.plugins.RateLimiters
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class GoogleLoginRequest(
    val code: String,
    val redirectUri: String,
    /** The state from `GET /auth/google/start`, as Google returned it (AOS-618). */
    val state: String? = null
)

@Serializable
data class GoogleLoginStartResponse(val url: String)

/**
 * What signing in with Google needs besides the auth service (AOS-618, AUD-06/16/19): the Google client (consent
 * URL), the server-side state + PKCE store, and the lockout for refused attempts.
 */
class LoginOAuth(
    val google: GoogleOAuthClient,
    val states: dev.adminos.api.domain.ingestion.connection.OAuthStateService,
    val guard: SignInGuard = SignInGuard(),
    /** The second-factor step a new session leads to (AOS-617); the session itself is pending either way. */
    val secondFactorStep: suspend (UUID) -> SecondFactorStep = { SecondFactorStep.VERIFY }
)

/**
 * Tokens travel only in HttpOnly cookies (AuthCookies), never in response bodies, so page
 * JavaScript (and any XSS) can't read them. Non-browser clients get a token flow in AOS-604.
 */
@Serializable
data class AuthTokenResponse(
    val expiresIn: Long,
    val user: UserResponse,
    /** What this new session must do before it can use the app (AOS-617): `enrol` a first passkey, or `verify` one. */
    val secondFactor: String = "verify"
)

@Serializable
data class AccessTokenResponse(
    val expiresIn: Long
)

@Serializable
data class SessionResponse(
    val id: String,
    val ipAddress: String?,
    val userAgent: String?,
    val lastActiveAt: String?,
    val createdAt: String,
    /** A coarse name such as "Chrome on Mac" (AOS-620); the raw user agent stays in [userAgent]. */
    val device: String = "Unknown device",
    /** True for the session making this request. */
    val current: Boolean = false
)

@Serializable
data class RevokeAllResponse(val revoked: Int, val signedOut: Boolean)

fun Route.authRoutes(authService: AuthService, accessTokenTtl: Long, config: AppConfig, auditService: AuditService, login: LoginOAuth) {
    val cookies = AuthCookies(config)
    val authLimiter = RateLimiters.auth()
    val callbackUri = "${config.app.appUrl.trimEnd('/')}/auth/callback"

    route("/auth") {

        // Rate limit: 10 req/min per IP on all auth endpoints
        install(RateLimitPlugin) {
            limiter = authLimiter
            keyExtractor = { call -> call.clientIp }
        }

        // Public routes (no JWT required)

        // Starts a Google sign-in (AOS-618, AUD-06): a fresh single-use state (server-side, hashed) with a PKCE
        // challenge, bound to this browser by an HttpOnly cookie. Identity scopes only (AUD-19).
        get("/google/start") {
            call.lockedOut(login.guard, call.clientIp)
            val issued = login.states.issue(dev.adminos.api.domain.ingestion.connection.OAuthStateClaim.login(callbackUri))
            val url = login.google.loginConsentUrl(callbackUri, issued.state, issued.codeChallenge)
                ?: throw ApiException(HttpStatusCode.ServiceUnavailable, ApiError("AUTH_008", "Google sign-in is not configured"))
            cookies.setLoginState(call, issued.state)
            call.respond(HttpStatusCode.OK, ApiResponse.success(GoogleLoginStartResponse(url), call.requestId))
        }

        post("/google") {
            val ipAddress = call.clientIp
            call.lockedOut(login.guard, ipAddress)
            val request = call.receive<GoogleLoginRequest>()

            // Every refusal below counts towards the lockout; only the owner's success clears it.
            fun refuse(status: HttpStatusCode, error: ApiError): Nothing {
                login.guard.refused(ipAddress)
                throw ApiException(status, error)
            }

            // Item 3: Validate redirectUri against allowed list (OWASP OAuth Cheat Sheet)
            if (request.redirectUri != callbackUri) refuse(HttpStatusCode.BadRequest, ApiError("AUTH_005", "Invalid redirect URI"))

            // The state must be the one this browser was given (cookie) and still unused server-side (AUD-06).
            val presented = request.state
            val bound = cookies.loginState(call)
            cookies.clearLoginState(call)
            if (presented == null || bound == null || !constantTimeEquals(presented, bound)) refuse(HttpStatusCode.BadRequest, LOGIN_STATE_INVALID)
            val codeVerifier = try {
                login.states.consume(presented, dev.adminos.api.domain.ingestion.connection.OAuthStateClaim.login(callbackUri))
            } catch (e: dev.adminos.api.domain.ingestion.connection.OAuthStateException) {
                refuse(HttpStatusCode.BadRequest, LOGIN_STATE_INVALID)
            }

            val userAgent = call.request.userAgent()

            try {
                val result = authService.loginWithGoogle(
                    code = request.code,
                    redirectUri = request.redirectUri,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                    codeVerifier = codeVerifier
                )
                login.guard.succeeded(ipAddress)

                // Session cookies: HttpOnly, SameSite=Strict (OWASP Session Management)
                cookies.setSession(call, result.accessToken, result.refreshToken)

                // Item 7: Audit login
                auditService.log(
                    userId = result.user.id,
                    actor = ActorType.USER,
                    action = AuditActions.AUTH_LOGIN,
                    entityType = "session",
                    ipAddress = ipAddress
                )

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        AuthTokenResponse(
                            expiresIn = result.expiresIn,
                            user = UserResponse.from(result.user),
                            secondFactor = login.secondFactorStep(result.user.id).name.lowercase()
                        ),
                        call.requestId
                    )
                )
            } catch (e: UserInactiveException) {
                refuse(HttpStatusCode.Forbidden, ApiError.USER_INACTIVE)
            } catch (e: NotOwnerException) {
                refuse(HttpStatusCode.Forbidden, ApiError.NOT_OWNER)
            } catch (e: RuntimeException) {
                refuse(HttpStatusCode.Unauthorized, ApiError.OAUTH_FAILED)
            }
        }

        // Uses the HttpOnly refresh cookie; sets a fresh access cookie.
        post("/refresh") {
            val refreshToken = cookies.refreshToken(call)
                ?: throw ApiException(HttpStatusCode.Unauthorized, ApiError.TOKEN_INVALID)
            try {
                val refreshed = authService.refreshToken(refreshToken)
                cookies.setAccess(call, refreshed.accessToken)
                // Rotation (AOS-620): the old refresh token stops working; reusing it later revokes the session.
                refreshed.refreshToken?.let { cookies.setRefresh(call, it) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(AccessTokenResponse(expiresIn = accessTokenTtl), call.requestId))
            } catch (e: RefreshTokenReusedException) {
                cookies.clear(call)
                auditService.log(userId = null, actor = ActorType.SYSTEM, action = AuditActions.AUTH_SESSION_REVOKE, entityType = "session", ipAddress = call.clientIp)
                throw ApiException(HttpStatusCode.Unauthorized, REFRESH_REUSED)
            } catch (e: SessionNotFoundException) {
                cookies.clear(call)
                throw ApiException(HttpStatusCode.Unauthorized, ApiError.TOKEN_INVALID)
            } catch (e: SessionRevokedException) {
                cookies.clear(call)
                throw ApiException(HttpStatusCode.Unauthorized, ApiError.SESSION_REVOKED)
            } catch (e: UserNotFoundException) {
                cookies.clear(call)
                throw ApiException(HttpStatusCode.Unauthorized, ApiError.USER_NOT_FOUND)
            } catch (e: UserInactiveException) {
                cookies.clear(call)
                throw ApiException(HttpStatusCode.Forbidden, ApiError.USER_INACTIVE)
            } catch (e: NotOwnerException) {
                cookies.clear(call)
                throw ApiException(HttpStatusCode.Forbidden, ApiError.NOT_OWNER)
            }
        }

        // Public: must work after the access JWT has expired. Revokes only this device's session.
        post("/logout") {
            val userId = cookies.refreshToken(call)?.let { authService.logoutByRefreshToken(it) }
            cookies.clear(call)
            if (userId != null) {
                auditService.log(
                    userId = userId,
                    actor = ActorType.USER,
                    action = AuditActions.AUTH_LOGOUT,
                    entityType = "session",
                    ipAddress = call.clientIp
                )
            }
            call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("message" to "Logged out"), call.requestId))
        }

        // Protected routes (JWT required)

        authenticate("auth-jwt") {

            get("/sessions") {
                val principal = call.userPrincipal
                val sessions = authService.getActiveSessions(principal.userId)

                val response = sessions.sortedByDescending { it.lastActiveAt ?: it.createdAt }.map { session ->
                    SessionResponse(
                        id = session.id.toString(),
                        ipAddress = session.ipAddress,
                        userAgent = session.userAgent,
                        lastActiveAt = session.lastActiveAt?.toString(),
                        createdAt = session.createdAt.toString(),
                        device = DeviceLabel.of(session.userAgent),
                        current = session.id == principal.sessionId
                    )
                }

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(response, call.requestId)
                )
            }

            // Sign out everywhere (AOS-620): every session ends at once, access tokens included (they carry their session).
            // `?keepCurrent=true` keeps this device signed in.
            post("/sessions/revoke-all") {
                val principal = call.userPrincipal
                val keep = call.request.queryParameters["keepCurrent"] == "true"
                val except = if (keep) principal.sessionId else null
                val revoked = authService.revokeAll(principal.userId, except)
                auditService.log(userId = principal.userId, actor = ActorType.USER, action = AuditActions.AUTH_SESSION_REVOKE,
                    entityType = "session", ipAddress = call.clientIp)
                val signedOut = except == null
                if (signedOut) cookies.clear(call)
                call.respond(HttpStatusCode.OK, ApiResponse.success(RevokeAllResponse(revoked, signedOut), call.requestId))
            }

            delete("/sessions/{id}") {
                val principal = call.userPrincipal
                val ipAddress = call.clientIp
                val sessionId = call.parameters["id"]
                    ?: throw ApiException(HttpStatusCode.BadRequest, ApiError.TOKEN_INVALID)

                val sessionUuid = UUID.fromString(sessionId) // malformed → 400 (IllegalArgumentException)
                // Only your own sessions; anything else is "not found", and nothing is audited (AOS-610).
                if (!authService.revokeSession(principal.userId, sessionUuid)) {
                    throw ApiException(HttpStatusCode.NotFound, ApiError.SESSION_NOT_FOUND)
                }

                // Item 7: Audit session revoke
                auditService.log(
                    userId = principal.userId,
                    actor = ActorType.USER,
                    action = AuditActions.AUTH_SESSION_REVOKE,
                    entityType = "session",
                    entityId = sessionUuid,
                    ipAddress = ipAddress
                )
                // Signing out this very device: its cookies go too.
                if (sessionUuid == principal.sessionId) cookies.clear(call)

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(mapOf("message" to "Session revoked"), call.requestId)
                )
            }
        }
    }
}

/** A sign-in that didn't start in this browser, was already used, or expired (AOS-618). One answer for all. */
/** A rotated refresh token came back: the session was ended (AOS-620). */
val REFRESH_REUSED = ApiError("AUTH_009", "This session was signed out for your safety. Sign in again.")

val LOGIN_STATE_INVALID = ApiError("AUTH_007", "This sign-in didn't start here or has expired. Start again.")

/** 429 with Retry-After while [client] is locked out after refused sign-ins (AOS-618). */
private fun ApplicationCall.lockedOut(guard: SignInGuard, client: String) {
    val wait = guard.lockedFor(client) ?: return
    val seconds = wait.seconds.coerceAtLeast(1)
    response.header(HttpHeaders.RetryAfter, seconds.toString())
    throw ApiException(HttpStatusCode.TooManyRequests, ApiError("RATE_002", "Too many refused sign-ins. Try again in ${(seconds + 59) / 60} min."))
}

/** Compares secrets without leaking, through timing, how much of them matched. */
internal fun constantTimeEquals(a: String, b: String): Boolean =
    java.security.MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
