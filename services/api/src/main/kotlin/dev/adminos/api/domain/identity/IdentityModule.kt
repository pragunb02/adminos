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

    routing {
        route("/api/v1") {
            authRoutes(authService, config.auth.accessTokenTtlSeconds, config, auditService)
        }
    }
}
