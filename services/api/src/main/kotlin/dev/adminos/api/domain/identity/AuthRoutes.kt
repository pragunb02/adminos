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
    val redirectUri: String
)

/**
 * Tokens travel only in HttpOnly cookies (AuthCookies), never in response bodies, so page
 * JavaScript (and any XSS) can't read them. Non-browser clients get a token flow in AOS-604.
 */
@Serializable
data class AuthTokenResponse(
    val expiresIn: Long,
    val user: UserResponse
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
    val createdAt: String
)

fun Route.authRoutes(authService: AuthService, accessTokenTtl: Long, config: AppConfig, auditService: AuditService) {
    val cookies = AuthCookies(config)

    route("/auth") {

        // Rate limit: 10 req/min per IP on all auth endpoints
        install(RateLimitPlugin) {
            limiter = RateLimiters.auth
            keyExtractor = { call -> call.clientIp }
        }

        // Public routes (no JWT required)

        post("/google") {
            val request = call.receive<GoogleLoginRequest>()

            // Item 3: Validate redirectUri against allowed list (OWASP OAuth Cheat Sheet)
            val allowedRedirectUri = "${config.app.appUrl}/auth/callback"
            if (request.redirectUri != allowedRedirectUri) {
                throw ApiException(HttpStatusCode.BadRequest, ApiError("AUTH_005", "Invalid redirect URI"))
            }

            val ipAddress = call.clientIp
            val userAgent = call.request.userAgent()

            try {
                val result = authService.loginWithGoogle(
                    code = request.code,
                    redirectUri = request.redirectUri,
                    ipAddress = ipAddress,
                    userAgent = userAgent
                )

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
                            user = UserResponse.from(result.user)
                        ),
                        call.requestId
                    )
                )
            } catch (e: UserInactiveException) {
                throw ApiException(HttpStatusCode.Forbidden, ApiError.USER_INACTIVE)
            } catch (e: NotOwnerException) {
                throw ApiException(HttpStatusCode.Forbidden, ApiError.NOT_OWNER)
            } catch (e: RuntimeException) {
                throw ApiException(HttpStatusCode.Unauthorized, ApiError.OAUTH_FAILED)
            }
        }

        // Uses the HttpOnly refresh cookie; sets a fresh access cookie.
        post("/refresh") {
            val refreshToken = cookies.refreshToken(call)
                ?: throw ApiException(HttpStatusCode.Unauthorized, ApiError.TOKEN_INVALID)
            try {
                cookies.setAccess(call, authService.refreshToken(refreshToken))
                call.respond(HttpStatusCode.OK, ApiResponse.success(AccessTokenResponse(expiresIn = accessTokenTtl), call.requestId))
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

                val response = sessions.map { session ->
                    SessionResponse(
                        id = session.id.toString(),
                        ipAddress = session.ipAddress,
                        userAgent = session.userAgent,
                        lastActiveAt = session.lastActiveAt?.toString(),
                        createdAt = session.createdAt.toString()
                    )
                }

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(response, call.requestId)
                )
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

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(mapOf("message" to "Session revoked"), call.requestId)
                )
            }
        }
    }
}
