package dev.adminos.api.domain.ingestion

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.identity.GoogleOAuthClient
import dev.adminos.api.domain.ingestion.cash.CashIntake
import dev.adminos.api.domain.ingestion.cash.cashRoutes
import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.connectionRoutes
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.ingestEventRoutes
import dev.adminos.api.domain.ingestion.sms.SmsIntake
import dev.adminos.api.domain.ingestion.sms.rawSmsRoutes
import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.domain.ingestion.sync.ingestionRoutes
import dev.adminos.api.domain.ingestion.statements.StatementImportService
import dev.adminos.api.domain.ingestion.statements.statementRoutes
import dev.adminos.api.domain.ingestion.whatsapp.WhatsAppImportService
import dev.adminos.api.domain.ingestion.whatsapp.whatsAppImportRoutes
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
    val googleOAuthClient: GoogleOAuthClient by inject()
    val statementImportService: StatementImportService by inject()
    val ingestEventService: IngestEventService by inject()
    val smsIntake: SmsIntake by inject()
    val cashIntake: CashIntake by inject()
    val whatsAppImportService: WhatsAppImportService by inject()

    routing {
        route("/api/v1") {
            ingestionRoutes(ingestionService, connectionService)
            ingestEventRoutes(ingestEventService)
            rawSmsRoutes(smsIntake)
            cashRoutes(cashIntake)
            connectionRoutes(connectionService, auditService, googleOAuthClient)
            webhookRoutes(connectionService, ingestionService, config.auth.googlePubsubAudience, config.auth.googlePubsubServiceAccount)
            statementRoutes(statementImportService)
            whatsAppImportRoutes(whatsAppImportService)
        }
    }
}
