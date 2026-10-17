package dev.adminos.api.domain.identity

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.audit.AuditService
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Identity domain module — registers auth routes.
 * Dependencies are resolved via Koin (single instances, no duplication).
 */
fun Application.identityModule(config: AppConfig) {
    val authService: AuthService by inject()
    val auditService: AuditService by inject()
    val deviceTokenService: DeviceTokenService by inject()
    val google: GoogleOAuthClient by inject()
    val states: dev.adminos.api.domain.ingestion.connection.OAuthStateService by inject()
    val login = LoginOAuth(google, states)

    routing {
        route("/api/v1") {
            authRoutes(authService, config.auth.accessTokenTtlSeconds, config, auditService, login)
            deviceTokenRoutes(deviceTokenService)
        }
    }
}
