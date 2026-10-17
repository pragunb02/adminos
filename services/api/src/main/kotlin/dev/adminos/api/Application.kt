package dev.adminos.api

import dev.adminos.api.config.AppConfig
import dev.adminos.api.di.appModule
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.DeviceTokenService
import dev.adminos.api.domain.identity.configureDeviceAuthentication
import dev.adminos.api.domain.agent.agentModule
import dev.adminos.api.domain.financial.financialModule
import dev.adminos.api.domain.identity.identityModule
import dev.adminos.api.domain.ingestion.ingestionModule
import dev.adminos.api.domain.ingestion.pipeline.IngestProcessor
import dev.adminos.api.domain.notifications.notificationModule
import dev.adminos.api.domain.scheduling.JobScheduler
import dev.adminos.api.domain.documents.documentsModule
import dev.adminos.api.domain.ai.aiModule
import dev.adminos.api.domain.coach.coachModule
import dev.adminos.api.infrastructure.database.DatabaseFactory
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.koin.core.module.Module
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun main() {
    startServer(AppConfig.load(), wait = true)
}

/** Starts Netty on the configured host and port (separate from [main] so a test can start and stop it, AOS-312). */
internal fun startServer(config: AppConfig, wait: Boolean): EmbeddedServer<*, *> =
    embeddedServer(Netty, port = config.app.port, host = config.app.bindHost) {
        module(config)
    }.start(wait = wait)

/**
 * [diModule] and [useDatabase] default to the real environment; tests pass them to start the app
 * in the database configuration (testability seam, AOS-312).
 */
fun Application.module(
    config: AppConfig,
    diModule: Module = appModule,
    useDatabase: Boolean = DatabaseFactory.isConfigured()
) {
    // Install Koin DI
    install(Koin) {
        slf4jLogger()
        modules(diModule)
    }

    // Database: with DATABASE_URL, migrate and connect (startup fails if that fails).
    // Without it, the app runs on in-memory repositories (local dev only; see AppModule).
    if (useDatabase) {
        DatabaseFactory.init(config.database)
    } else {
        log.warn("DATABASE_URL not set: using IN-MEMORY repositories. Data is lost on restart (dev mode only).")
    }

    // Configure all Ktor plugins — JwtService from Koin (single instance)
    val jwtService: JwtService by inject()
    val authService: dev.adminos.api.domain.identity.AuthService by inject()
    // Every access token must belong to a live session; with real data (a database) there are no exceptions (AOS-620).
    configurePlugins(config, jwtService, SessionGate(requireSession = useDatabase) { user, session -> authService.sessionAccess(user, session) })
    val deviceTokenService: DeviceTokenService by inject()
    configureDeviceAuthentication(deviceTokenService)

    // Domain modules — each registers its own routes, using Koin for dependencies
    identityModule(config)
    ingestionModule(config)
    financialModule()
    agentModule()
    notificationModule()
    documentsModule()
    aiModule()
    coachModule()

    // Health check
    configureHealthRoutes()

    // Background processing of intake events (D-34); stops with the application.
    val ingestProcessor: IngestProcessor by inject()
    ingestProcessor.start(this)

    // Scheduled jobs (AOS-501): reminders, nudges, detectors; they stop with the application.
    if (config.scheduler.enabled) {
        val scheduler: JobScheduler by inject()
        scheduler.start(this, config.scheduler.tick)
    } else {
        log.warn("SCHEDULER_ENABLED=false: scheduled jobs (reminders, nudges, detection) will not run on this instance.")
    }
}
