package dev.adminos.api.domain.agent

import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.financial.SubscriptionRepository
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Agent domain module — registers anomaly, briefing, and subscription agent routes.
 * Dependencies are resolved via Koin (single instances).
 */
fun Application.agentModule() {
    val anomalyRepository: AnomalyRepository by inject()
    val briefingRepository: BriefingRepository by inject()
    val insightRepository: InsightRepository by inject()
    val subscriptionRepository: SubscriptionRepository by inject()
    val auditService: AuditService by inject()

    routing {
        route("/api/v1") {
            anomalyRoutes(anomalyRepository, auditService)
            briefingRoutes(briefingRepository, insightRepository)
            subscriptionAgentRoutes(subscriptionRepository, auditService)
        }
    }
}
