package dev.adminos.api.domain.identity

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.ActorType
import dev.adminos.api.domain.audit.AuditActions
import dev.adminos.api.infrastructure.plugins.ApiException
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

@Serializable
data class RefreshTokenRequest(
    val refreshToken: String
)

@Serializable
data class AuthTokenResponse(
    val refreshToken: String,
    val expiresIn: Long,
    val user: UserResponse
    // Note: accessToken is set via HttpOnly cookie for web clients.
    // Mobile clients should use the refresh token to get access tokens.
)

@Serializable
data class AccessTokenResponse(
    val accessToken: String,
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

    route("/auth") {

        // Rate limit: 10 req/min per IP on all auth endpoints
        install(RateLimitPlugin) {
            limiter = RateLimiters.auth
            keyExtractor = { call -> call.request.local.remoteHost }
        }

        // Public routes (no JWT required)

        post("/google") {
            val request = call.receive<GoogleLoginRequest>()

            // Item 3: Validate redirectUri against allowed list (OWASP OAuth Cheat Sheet)
            val allowedRedirectUri = "${config.app.appUrl}/auth/callback"
            if (request.redirectUri != allowedRedirectUri) {
                throw ApiException(HttpStatusCode.BadRequest, ApiError("AUTH_005", "Invalid redirect URI"))
            }

            val ipAddress = call.request.local.remoteHost
            val userAgent = call.request.userAgent()

            try {
                val result = authService.loginWithGoogle(
                    code = request.code,
                    redirectUri = request.redirectUri,
                    ipAddress = ipAddress,
                    userAgent = userAgent
                )

                // Item 2: Set JWT in HttpOnly cookie (OWASP Session Management)
                call.response.cookies.append(
                    Cookie(
                        name = "access_token",
                        value = result.accessToken,
                        maxAge = result.expiresIn.toInt(),
                        path = "/",
                        httpOnly = true,
                        secure = !config.app.appUrl.startsWith("http://localhost"),
                        extensions = mapOf("SameSite" to "Strict")
                    )
                )

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
                            refreshToken = result.refreshToken,
                            expiresIn = result.expiresIn,
                            user = UserResponse.from(result.user)
                        ),
                        call.requestId
                    )
                )
            } catch (e: RuntimeException) {
                throw ApiException(HttpStatusCode.Unauthorized, ApiError.OAUTH_FAILED)
            }
        }

        post("/refresh") {
            val request = call.receive<RefreshTokenRequest>()

            try {
                val newAccessToken = authService.refreshToken(request.refreshToken)

                // Update HttpOnly cookie with new access token
                call.response.cookies.append(
                    Cookie(
                        name = "access_token",
                        value = newAccessToken,
                        maxAge = accessTokenTtl.toInt(),
                        path = "/",
                        httpOnly = true,
                        secure = !config.app.appUrl.startsWith("http://localhost"),
                        extensions = mapOf("SameSite" to "Strict")
                    )
                )

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        AccessTokenResponse(
                            accessToken = newAccessToken,
                            expiresIn = accessTokenTtl
                        ),
                        call.requestId
                    )
                )
            } catch (e: SessionNotFoundException) {
                throw ApiException(HttpStatusCode.Unauthorized, ApiError.TOKEN_INVALID)
            } catch (e: SessionRevokedException) {
                throw ApiException(HttpStatusCode.Unauthorized, ApiError.SESSION_REVOKED)
            } catch (e: UserNotFoundException) {
                throw ApiException(HttpStatusCode.Unauthorized, ApiError.USER_NOT_FOUND)
            }
        }

        // Protected routes (JWT required)

        authenticate("auth-jwt") {

            post("/logout") {
                val principal = call.userPrincipal
                val ipAddress = call.request.local.remoteHost
                // Revoke all active sessions for simplicity
                val sessions = authService.getActiveSessions(principal.userId)
                sessions.forEach { authService.revokeSession(principal.userId, it.id) }

                // Clear the HttpOnly cookie
                call.response.cookies.append(
                    Cookie(
                        name = "access_token",
                        value = "",
                        maxAge = 0,
                        path = "/",
                        httpOnly = true,
                        secure = !config.app.appUrl.startsWith("http://localhost"),
                        extensions = mapOf("SameSite" to "Strict")
                    )
                )

                // Item 7: Audit logout
                auditService.log(
                    userId = principal.userId,
                    actor = ActorType.USER,
                    action = AuditActions.AUTH_LOGOUT,
                    entityType = "session",
                    ipAddress = ipAddress
                )

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(mapOf("message" to "Logged out"), call.requestId)
                )
            }

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
                val ipAddress = call.request.local.remoteHost
                val sessionId = call.parameters["id"]
                    ?: throw ApiException(HttpStatusCode.BadRequest, ApiError.TOKEN_INVALID)

                authService.revokeSession(principal.userId, UUID.fromString(sessionId))

                // Item 7: Audit session revoke
                auditService.log(
                    userId = principal.userId,
                    actor = ActorType.USER,
                    action = AuditActions.AUTH_SESSION_REVOKE,
                    entityType = "session",
                    entityId = UUID.fromString(sessionId),
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
