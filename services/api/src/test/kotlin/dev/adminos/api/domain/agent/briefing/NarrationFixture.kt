package dev.adminos.api.domain.agent.briefing

import dev.adminos.api.domain.ai.AiCircuitBreaker
import dev.adminos.api.domain.ai.AiConsentCheck
import dev.adminos.api.domain.ai.AiCostGuard
import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiProvider
import dev.adminos.api.domain.ai.AiRoute
import dev.adminos.api.domain.ai.FakeAiProvider
import dev.adminos.api.domain.ai.InMemoryAiCallLogRepository
import dev.adminos.api.domain.ai.InMemoryAiSpendLedger
import dev.adminos.api.domain.analytics.CategoryShare
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.analytics.MerchantSpend
import dev.adminos.api.domain.financial.DueItem
import dev.adminos.api.domain.financial.DueKind
import dev.adminos.api.domain.financial.TransactionCategory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Shared parts for the briefing narration tests (AOS-716): a week of synthetic facts and a gateway around a fake model. */
object NarrationFixture {
    val WEEK = DateRange(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11))

    /** The same week as WeeklyBriefingTest's: ₹4,100.50 spent, ₹2,100.50 more than the week before. */
    fun facts(
        merchant: String = "Swiggy",
        due: String = "HDFC Credit Card",
        dormant: List<String> = emptyList()
    ) = BriefingFacts(
        period = WEEK,
        spent = BigDecimal("4100.50"), received = BigDecimal("75000.00"), previousSpent = BigDecimal("2000.00"),
        topCategories = listOf(
            share(TransactionCategory.FOOD, "2000.00", 2), share(TransactionCategory.SHOPPING, "1500.50", 1), share(TransactionCategory.TRANSPORT, "600.00", 1)
        ),
        topMerchant = MerchantSpend(merchant, BigDecimal("2000.00"), 2, TransactionCategory.FOOD),
        upcoming = listOf(DueItem(DueKind.BILL, UUID(0, 9), due, BigDecimal("18450.00"), date = LocalDate.of(2026, 10, 16), status = "upcoming", detail = "credit_card")),
        openAnomalies = 1, flaggedSubscriptions = 0, dormantMandates = dormant
    )

    /** A narration of [facts] that a careful model would write: every number and name copied. */
    const val GOOD = "Last week (Mon 5 Oct to Sun 11 Oct) you spent ₹4,100.50, ₹2,100.50 more than the week before. " +
        "Food took the most at ₹2,000, then Shopping (₹1,500.50) and Transport (₹600). Swiggy was your biggest payee: " +
        "₹2,000 over 2 payments. You received ₹75,000. In the next 7 days 1 bill of ₹18,450 is due, first HDFC Credit Card " +
        "on Fri 16 Oct. 1 unusual charge is waiting for your review."

    fun reply(narration: String): String = JsonObject(mapOf("narration" to JsonPrimitive(narration))).toString()

    fun share(category: TransactionCategory, spent: String, count: Int) = CategoryShare(category, BigDecimal(spent), 0.0, count, BigDecimal.ZERO)

    fun gatewayWithNoRoutes(clock: Clock): AiGateway =
        AiGateway(emptyList(), AiCostGuard(InMemoryAiSpendLedger(), 1_000_000, clock), InMemoryAiCallLogRepository(), { _, _ -> false }, AiCircuitBreaker(clock), clock)

    fun gateway(
        provider: AiProvider,
        consents: AiConsentCheck,
        clock: Clock,
        calls: InMemoryAiCallLogRepository = InMemoryAiCallLogRepository(),
        capMicros: Long = 1_000_000
    ): AiGateway = AiGateway(
        listOf(AiRoute.of(AiFeature.BRIEFING, provider, FakeAiProvider.MODEL)),
        AiCostGuard(InMemoryAiSpendLedger(), capMicros, clock), calls, consents, AiCircuitBreaker(clock), clock
    )
}
