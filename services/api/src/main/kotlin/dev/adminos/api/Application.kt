package dev.adminos.api

import dev.adminos.api.config.AppConfig
import dev.adminos.api.di.appModule
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.financial.financialModule
import dev.adminos.api.domain.identity.identityModule
import dev.adminos.api.domain.ingestion.ingestionModule
import dev.adminos.api.infrastructure.database.DatabaseFactory
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun main() {
    val config = AppConfig.load()

    embeddedServer(Netty, port = config.app.port, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}

fun Application.module(config: AppConfig) {
    // Install Koin DI
    install(Koin) {
        slf4jLogger()
        modules(appModule)
    }

    // Initialize database (Flyway migrations + connection pool)
    // Wrapped in try-catch so the app can start without DB for development
    try {
        DatabaseFactory.init(config.database)
    } catch (e: Exception) {
        log.warn("Database initialization skipped: ${e.message}")
    }

    // Configure all Ktor plugins — JwtService from Koin (single instance)
    val jwtService: JwtService by inject()
    configurePlugins(config, jwtService)

    // Domain modules — each registers its own routes, using Koin for dependencies
    identityModule(config)
    ingestionModule(config)
    financialModule()

    // Health check
    configureHealthRoutes()
}
