package dev.adminos.api.domain.ingestion

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.connectionRoutes
import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.domain.ingestion.sync.ingestionRoutes
import dev.adminos.api.domain.ingestion.upload.uploadRoutes
import dev.adminos.api.domain.ingestion.webhook.webhookRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Ingestion domain module — registers ingestion, connection, webhook, and upload routes.
 * Dependencies are resolved via Koin (single instances, no duplication).
 */
fun Application.ingestionModule(config: AppConfig) {
    val ingestionService: IngestionService by inject()
    val connectionService: ConnectionService by inject()
    val auditService: AuditService by inject()

    routing {
        route("/api/v1") {
            ingestionRoutes(ingestionService, connectionService)
            connectionRoutes(connectionService, auditService)
            webhookRoutes(connectionService, ingestionService, config.auth.googlePubsubAudience)
            uploadRoutes(ingestionService, connectionService)
        }
    }
}
