package dev.adminos.api.domain.ingestion

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.identity.GoogleOAuthClient
import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.connectionRoutes
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.ingestEventRoutes
import dev.adminos.api.domain.ingestion.sms.SmsIntake
import dev.adminos.api.domain.ingestion.sms.rawSmsRoutes
import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.domain.ingestion.sync.ingestionRoutes
import dev.adminos.api.domain.ingestion.upload.uploadRoutes
import dev.adminos.api.domain.ingestion.webhook.webhookRoutes
import dev.adminos.api.infrastructure.storage.StorageClient
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
    val googleOAuthClient: GoogleOAuthClient by inject()
    val storageClient: StorageClient by inject()
    val ingestEventService: IngestEventService by inject()
    val smsIntake: SmsIntake by inject()

    routing {
        route("/api/v1") {
            ingestionRoutes(ingestionService, connectionService)
            ingestEventRoutes(ingestEventService)
            rawSmsRoutes(smsIntake)
            connectionRoutes(connectionService, auditService, googleOAuthClient)
            webhookRoutes(connectionService, ingestionService, config.auth.googlePubsubAudience)
            uploadRoutes(ingestionService, connectionService, storageClient)
        }
    }
}
