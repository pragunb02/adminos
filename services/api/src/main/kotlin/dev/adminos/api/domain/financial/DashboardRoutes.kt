package dev.adminos.api.domain.financial

import dev.adminos.api.domain.agent.*
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

fun Route.dashboardRoutes(
    transactionRepository: TransactionRepository,
    subscriptionRepository: SubscriptionRepository,
    billTrackingService: BillTrackingService,
    anomalyRepository: AnomalyRepository,
    briefingRepository: BriefingRepository,
    insightRepository: InsightRepository
) {
    authenticate("auth-jwt") {
        route("/dashboard") {

            // GET /api/v1/dashboard/home
            get("/home") {
                val principal = call.userPrincipal
                val userId = principal.userId
                val now = Instant.now()
                val weekAgo = now.minus(7, ChronoUnit.DAYS)

                // Aggregate data
                val spendingMap = transactionRepository.spendingByCategory(userId, weekAgo, now)
                val totalSpentThisWeek = spendingMap.values.fold(java.math.BigDecimal.ZERO) { acc, v -> acc.add(v) }

                val activeSubCount = subscriptionRepository.countByUserId(userId, SubscriptionStatus.ACTIVE)
                val flaggedSubs = subscriptionRepository.findByUserId(userId, status = null, limit = 100)
                    .filter { it.isFlagged }
                val flaggedSubCount = flaggedSubs.size.toLong()

                val upcomingBills = billTrackingService.getUpcomingBills(userId)
                val billsDueWithin7 = upcomingBills.filter {
                    val daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), it.dueDate)
                    daysUntil in 0..7
                }

                val openAnomalies = anomalyRepository.findByUserId(userId, AnomalyStatus.OPEN, limit = 50)
                val latestBriefing = briefingRepository.findLatestByUserId(userId)

                // Build action items
                val actionItems = mutableListOf<DashboardActionItem>()

                // Anomalies
                openAnomalies.forEach { a ->
                    actionItems.add(
                        DashboardActionItem(
                            id = a.id.toString(),
                            type = "anomaly",
                            title = "Suspicious transaction detected",
                            description = a.reason,
                            severity = "critical",
                            entityId = a.id.toString(),
                            actionLabel = "Review"
                        )
                    )
                }

                // Flagged subscriptions
                flaggedSubs.forEach { s ->
                    actionItems.add(
                        DashboardActionItem(
                            id = s.id.toString(),
                            type = "subscription",
                            title = "Potentially unused: ${s.name}",
                            description = s.flaggedReason ?: "This subscription may be unused",
                            severity = "warning",
                            entityId = s.id.toString(),
                            actionLabel = "Review"
                        )
                    )
                }

                // Bills due within 7 days
                billsDueWithin7.forEach { b ->
                    val daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), b.dueDate)
                    val desc = when {
                        daysUntil <= 0 -> "Due today — ₹${b.amount}"
                        daysUntil == 1L -> "Due tomorrow — ₹${b.amount}"
                        else -> "Due in $daysUntil days — ₹${b.amount}"
                    }
                    actionItems.add(
                        DashboardActionItem(
                            id = b.id.toString(),
                            type = "bill",
                            title = "${b.billerName} bill",
                            description = desc,
                            severity = if (daysUntil <= 1) "warning" else "info",
                            entityId = b.id.toString(),
                            actionLabel = "View"
                        )
                    )
                }

                // Latest briefing action
                if (latestBriefing != null && latestBriefing.status == BriefingStatus.GENERATED) {
                    actionItems.add(
                        DashboardActionItem(
                            id = latestBriefing.id.toString(),
                            type = "briefing",
                            title = "New weekly briefing",
                            description = "Your weekly financial summary is ready",
                            severity = "info",
                            entityId = latestBriefing.id.toString(),
                            actionLabel = "Read"
                        )
                    )
                }

                // Build briefing summary
                val briefingSummary = latestBriefing?.let {
                    DashboardBriefingSummary(
                        id = it.id.toString(),
                        periodStart = it.periodStart.toString(),
                        periodEnd = it.periodEnd.toString(),
                        content = it.content.take(300),
                        totalSpent = it.totalSpent?.toDouble()
                    )
                }

                // Greeting
                val hour = now.atZone(ZoneId.of("Asia/Kolkata")).hour
                val greeting = when {
                    hour < 12 -> "Good morning"
                    hour < 17 -> "Good afternoon"
                    else -> "Good evening"
                }

                val response = DashboardHomeResponse(
                    greeting = greeting,
                    summary = DashboardSummaryResponse(
                        totalSpentThisWeek = totalSpentThisWeek.toDouble(),
                        activeSubscriptions = activeSubCount,
                        flaggedSubscriptions = flaggedSubCount,
                        upcomingBills = upcomingBills.size.toLong(),
                        openAnomalies = openAnomalies.size.toLong()
                    ),
                    actionItems = actionItems,
                    latestBriefing = briefingSummary
                )

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(response, call.requestId)
                )
            }
        }
    }
}

// ── Dashboard DTOs ──

@Serializable
data class DashboardHomeResponse(
    val greeting: String,
    val summary: DashboardSummaryResponse,
    val actionItems: List<DashboardActionItem>,
    val latestBriefing: DashboardBriefingSummary?
)

@Serializable
data class DashboardSummaryResponse(
    val totalSpentThisWeek: Double,
    val activeSubscriptions: Long,
    val flaggedSubscriptions: Long,
    val upcomingBills: Long,
    val openAnomalies: Long
)

@Serializable
data class DashboardActionItem(
    val id: String,
    val type: String,
    val title: String,
    val description: String,
    val severity: String,
    val entityId: String,
    val actionLabel: String
)

@Serializable
data class DashboardBriefingSummary(
    val id: String,
    val periodStart: String,
    val periodEnd: String,
    val content: String,
    val totalSpent: Double?
)
