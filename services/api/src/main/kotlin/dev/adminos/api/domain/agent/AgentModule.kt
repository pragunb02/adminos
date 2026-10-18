package dev.adminos.api.domain.agent

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Agent domain module — registers anomaly, briefing, and subscription agent routes.
 * Dependencies are resolved via Koin (single instances).
 */
fun Application.agentModule() {
    val anomalyReviewService: AnomalyReviewService by inject()
    val briefingRepository: BriefingRepository by inject()
    val insightRepository: InsightRepository by inject()
    val subscriptionReviewService: SubscriptionReviewService by inject()

    routing {
        route("/api/v1") {
            anomalyRoutes(anomalyReviewService)
            briefingRoutes(briefingRepository, insightRepository)
            subscriptionAgentRoutes(subscriptionReviewService)
        }
    }
}
