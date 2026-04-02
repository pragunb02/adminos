package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.identity.JwtService
import io.ktor.http.*
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

/**
 * Custom JWT authentication using nimbus-jose-jwt.
 * Extracts Bearer token from Authorization header, verifies with JwtService.
 */
fun Application.configureAuthentication(jwtService: JwtService) {
    install(Authentication) {
        bearer("auth-jwt") {
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
