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
    val role: String
) : Principal

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
fun Application.configureAuthentication(jwtService: JwtService) {
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
                if (claims != null) {
                    UserPrincipal(
                        userId = claims.userId,
                        email = claims.email,
                        role = claims.role
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
