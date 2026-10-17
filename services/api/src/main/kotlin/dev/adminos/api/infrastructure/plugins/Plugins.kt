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
    // First, so everything after sees the client IP. Only behind an edge proxy that appends the real peer: without one,
    // X-Forwarded-For is whatever the client sent (verified through the Next proxy, AUD-03), so the TCP peer is used.
    if (config.app.trustForwardedFor) configureForwardedHeaders()
    else log.info("TRUST_FORWARDED_FOR is off: client IPs are the connection peer (set it only behind an edge proxy that appends X-Forwarded-For)")
    configureRequestId()
    configureRequestBodyLimit() // before anything reads a body, including unauthenticated routes (AOS-615)
    configureSerialization()
    configureHTTP(config.app.appUrl)
    configureLogging()
    configureErrorHandling()
    configureAuthentication(jwtService)
}
