package dev.adminos.api.domain.agent

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ── Enums ──

enum class AnomalyType { LARGE_AMOUNT, FOREIGN_CURRENCY, ODD_HOURS, CARD_TESTING, DUPLICATE_CHARGE }
enum class AnomalyStatus { OPEN, CONFIRMED_SAFE, CONFIRMED_FRAUD, DISMISSED }
enum class AnomalyResolver { USER, SYSTEM, AGENT }
enum class BriefingType { WEEKLY }
enum class BriefingStatus { GENERATED, DELIVERED, OPENED }
enum class InsightType { SUBSCRIPTION_WASTE, BILL_DUE, ANOMALY, SPENDING_SPIKE }
enum class Severity { INFO, WARNING, CRITICAL }
enum class InsightActionType { NONE, CANCEL_SUB, PAY_BILL, REVIEW_ANOMALY }
enum class InsightStatus { PENDING, SEEN, ACTED, DISMISSED }

// ── Domain Models ──

data class Anomaly(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val transactionId: UUID,
    val type: AnomalyType,
    val confidenceScore: Double,
    val reason: String,
    val agentExplanation: String? = null,
    val status: AnomalyStatus = AnomalyStatus.OPEN,
    val resolvedAt: Instant? = null,
    val resolvedBy: AnomalyResolver? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

data class Briefing(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val type: BriefingType = BriefingType.WEEKLY,
    val content: String,
    val contentStructured: Map<String, Any?>? = null,
    val totalSpent: BigDecimal? = null,
    val totalIncome: BigDecimal? = null,
    val topCategories: List<CategoryAmount>? = null,
    val subscriptionsFlagged: Int = 0,
    val anomaliesDetected: Int = 0,
    val billsUpcoming: Int = 0,
    val status: BriefingStatus = BriefingStatus.GENERATED,
    val deliveredAt: Instant? = null,
    val openedAt: Instant? = null,
    val modelUsed: String,
    val promptVersion: String,
    val tokensUsed: Int,
    val generationMs: Int? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)


data class CategoryAmount(
    val category: String,
    val amount: BigDecimal
)

data class Insight(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val briefingId: UUID? = null,
    val type: InsightType,
    val title: String,
    val body: String,
    val severity: Severity = Severity.INFO,
    val entityType: String? = null,
    val entityId: UUID? = null,
    val actionType: InsightActionType = InsightActionType.NONE,
    val actionPayload: Map<String, Any?>? = null,
    val status: InsightStatus = InsightStatus.PENDING,
    val seenAt: Instant? = null,
    val actedAt: Instant? = null,
    val dismissedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
