package dev.adminos.api.domain.financial

import dev.adminos.api.domain.agent.AnomalyRepository
import dev.adminos.api.domain.agent.BriefingRepository
import dev.adminos.api.domain.agent.InsightRepository
import dev.adminos.api.domain.analytics.analyticsRoutes
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.financial.links.suggestionRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*
import dev.adminos.api.domain.financial.mandates.mandateRoutes
import dev.adminos.api.domain.financial.receipts.receiptRoutes
import org.koin.ktor.ext.inject

/**
 * Financial domain module — registers transaction, account, subscription, bill, and dashboard routes.
 * Dependencies are resolved via Koin (single instances).
 */
fun Application.financialModule() {
    val transactionRepository: TransactionRepository by inject()
    val subscriptionRepository: SubscriptionRepository by inject()
    val categoryCorrections: CategoryCorrectionService by inject()
    val accountDiscoveryService: AccountDiscoveryService by inject()
    val billTrackingService: BillTrackingService by inject()
    val auditService: AuditService by inject()
    val anomalyRepository: AnomalyRepository by inject()
    val briefingRepository: BriefingRepository by inject()
    val insightRepository: InsightRepository by inject()
    val suggestionService: dev.adminos.api.domain.financial.links.SuggestionService by inject()
    val analyticsService: dev.adminos.api.domain.analytics.AnalyticsService by inject()
    val duesService: DuesService by inject()
    val mandateService: dev.adminos.api.domain.financial.mandates.MandateService by inject()
    val hiddenFees: dev.adminos.api.domain.financial.receipts.HiddenFeesService by inject()
    val receiptMatching: dev.adminos.api.domain.financial.receipts.ReceiptMatchingService by inject()

    routing {
        route("/api/v1") {
            transactionRoutes(transactionRepository, categoryCorrections, auditService)
            suggestionRoutes(suggestionService)
            analyticsRoutes(analyticsService)
            accountRoutes(accountDiscoveryService)
            subscriptionRoutes(subscriptionRepository)
            billRoutes(billTrackingService)
            duesRoutes(duesService)
            mandateRoutes(mandateService)
            receiptRoutes(hiddenFees, receiptMatching)
            dashboardRoutes(
                transactionRepository,
                subscriptionRepository,
                billTrackingService,
                anomalyRepository,
                briefingRepository,
                insightRepository
            )
        }
    }
}
