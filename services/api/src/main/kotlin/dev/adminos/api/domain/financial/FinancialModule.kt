package dev.adminos.api.domain.financial

import dev.adminos.api.domain.audit.AuditService
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Financial domain module — registers transaction, account, subscription, and bill routes.
 * Dependencies are resolved via Koin (single instances).
 */
fun Application.financialModule() {
    val transactionRepository: TransactionRepository by inject()
    val subscriptionRepository: SubscriptionRepository by inject()
    val categorizationService: CategorizationService by inject()
    val accountDiscoveryService: AccountDiscoveryService by inject()
    val billTrackingService: BillTrackingService by inject()
    val auditService: AuditService by inject()

    routing {
        route("/api/v1") {
            transactionRoutes(transactionRepository, categorizationService, auditService)
            accountRoutes(accountDiscoveryService)
            subscriptionRoutes(subscriptionRepository)
            billRoutes(billTrackingService)
        }
    }
}
