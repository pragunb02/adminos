package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.identity.JwtService
import io.ktor.server.application.*

/**
 * Configures all Ktor plugins in one place.
 * This is the single entry point for plugin installation,
 * following the pattern from JetBrains' Ktor DDD guide.
 */
fun Application.configurePlugins(config: AppConfig, jwtService: JwtService) {
    configureForwardedHeaders() // first: everything after sees the real client IP (D-32)
    configureRequestId()
    configureRequestBodyLimit() // before anything reads a body, including unauthenticated routes (AOS-615)
    configureSerialization()
    configureHTTP(config.app.appUrl)
    configureLogging()
    configureErrorHandling()
    configureAuthentication(jwtService)
}
