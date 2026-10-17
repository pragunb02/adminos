package dev.adminos.api.domain.ingestion

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.identity.GoogleOAuthClient
import dev.adminos.api.domain.ingestion.cash.CashIntake
import dev.adminos.api.domain.ingestion.cash.CashPhraseParser
import dev.adminos.api.domain.ingestion.cash.cashPhraseRoutes
import dev.adminos.api.domain.ingestion.cash.cashRoutes
import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.GmailOAuth
import dev.adminos.api.domain.ingestion.connection.OAuthStateService
import dev.adminos.api.domain.ingestion.connection.connectionRoutes
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.ingestEventRoutes
import dev.adminos.api.domain.ingestion.fallback.MessageReviewService
import dev.adminos.api.domain.ingestion.fallback.messageReviewRoutes
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
/** This app's Connect Gmail callback page: the only redirect URI the API accepts for it (AOS-618). */
internal fun gmailCallbackUrl(appUrl: String) = appUrl.trimEnd('/') + "/connections/gmail/callback"

fun Application.ingestionModule(config: AppConfig) {
    val ingestionService: IngestionService by inject()
    val connectionService: ConnectionService by inject()
    val auditService: AuditService by inject()
    val googleOAuthClient: GoogleOAuthClient by inject()
    val oauthStates: OAuthStateService by inject()
    val statementImportService: StatementImportService by inject()
    val ingestEventService: IngestEventService by inject()
    val smsIntake: SmsIntake by inject()
    val cashIntake: CashIntake by inject()
    val cashPhrases: CashPhraseParser by inject()
    val whatsAppImportService: WhatsAppImportService by inject()
    val messageReviewService: MessageReviewService by inject()

    routing {
        route("/api/v1") {
            ingestionRoutes(ingestionService, connectionService)
            ingestEventRoutes(ingestEventService)
            rawSmsRoutes(smsIntake)
            cashRoutes(cashIntake)
            cashPhraseRoutes(cashPhrases)
            connectionRoutes(connectionService, auditService, GmailOAuth(googleOAuthClient, oauthStates, gmailCallbackUrl(config.app.appUrl)))
            webhookRoutes(connectionService, ingestionService, config.auth.googlePubsubAudience, config.auth.googlePubsubServiceAccount)
            statementRoutes(statementImportService)
            whatsAppImportRoutes(whatsAppImportService)
            messageReviewRoutes(messageReviewService)
        }
    }
}
