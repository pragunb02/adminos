package dev.adminos.api.domain.agent

import kotlinx.serialization.Serializable

// ── Request DTOs ──

@Serializable
data class AnomalyResolveRequest(
    val status: String // "confirmed_safe" or "confirmed_fraud"
)

@Serializable
data class SubscriptionDismissRequest(
    val reason: String? = null
)

// ── Response DTOs ──

@Serializable
data class AnomalyResponse(
    val id: String,
    val transactionId: String,
    val type: String,
    val confidenceScore: Double,
    val reason: String,
    val agentExplanation: String?,
    val status: String,
    val resolvedAt: String?,
    val resolvedBy: String?,
    val createdAt: String
) {
    companion object {
        fun from(a: Anomaly) = AnomalyResponse(
            id = a.id.toString(),
            transactionId = a.transactionId.toString(),
            type = a.type.name.lowercase(),
            confidenceScore = a.confidenceScore,
            reason = a.reason,
            agentExplanation = a.agentExplanation,
            status = a.status.name.lowercase(),
            resolvedAt = a.resolvedAt?.toString(),
            resolvedBy = a.resolvedBy?.name?.lowercase(),
            createdAt = a.createdAt.toString()
        )
    }
}

@Serializable
data class BriefingResponse(
    val id: String,
    val periodStart: String,
    val periodEnd: String,
    val type: String,
    val content: String,
    val totalSpent: Double?,
    val totalIncome: Double?,
    val topCategories: List<CategoryAmountResponse>?,
    val subscriptionsFlagged: Int,
    val anomaliesDetected: Int,
    val billsUpcoming: Int,
    val status: String,
    val insights: List<InsightResponse>? = null,
    val createdAt: String
) {
    companion object {
        fun from(b: Briefing, insights: List<Insight>? = null) = BriefingResponse(
            id = b.id.toString(),
            periodStart = b.periodStart.toString(),
            periodEnd = b.periodEnd.toString(),
            type = b.type.name.lowercase(),
            content = b.content,
            totalSpent = b.totalSpent?.toDouble(),
            totalIncome = b.totalIncome?.toDouble(),
            topCategories = b.topCategories?.map { CategoryAmountResponse(it.category, it.amount.toDouble()) },
            subscriptionsFlagged = b.subscriptionsFlagged,
            anomaliesDetected = b.anomaliesDetected,
            billsUpcoming = b.billsUpcoming,
            status = b.status.name.lowercase(),
            insights = insights?.map { InsightResponse.from(it) },
            createdAt = b.createdAt.toString()
        )
    }
}

@Serializable
data class CategoryAmountResponse(
    val category: String,
    val amount: Double
)

@Serializable
data class InsightResponse(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    val severity: String,
    val actionType: String,
    val entityId: String?,
    val status: String
) {
    companion object {
        fun from(i: Insight) = InsightResponse(
            id = i.id.toString(),
            type = i.type.name.lowercase(),
            title = i.title,
            body = i.body,
            severity = i.severity.name.lowercase(),
            actionType = i.actionType.name.lowercase(),
            entityId = i.entityId?.toString(),
            status = i.status.name.lowercase()
        )
    }
}
