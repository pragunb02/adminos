package dev.adminos.api.domain.ask

import dev.adminos.api.domain.ai.AiCompletion
import dev.adminos.api.domain.ai.AiCostGuard
import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiPrice
import dev.adminos.api.domain.ai.AiRequest
import dev.adminos.api.domain.ai.AiRoute
import dev.adminos.api.domain.ai.AiUsage
import dev.adminos.api.domain.ai.FakeAiProvider
import dev.adminos.api.domain.ai.InMemoryAiCallLogRepository
import dev.adminos.api.domain.ai.InMemoryAiSpendLedger
import dev.adminos.api.domain.ai.Micros
import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.InMemoryAnalyticsRepository
import dev.adminos.api.domain.ask.eval.AskSeed
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import kotlinx.coroutines.runBlocking

/**
 * Ask on the seeded synthetic DB (AOS-712) with a scripted model behind the real [AiGateway] (AOS-710): the plan
 * step answers [plan], the answer step answers [answer], and every request is recorded, so tests can check
 * exactly what would have left AdminOS.
 */
class AskFixture(
    var plan: (AiRequest) -> AiCompletion = { completed("""{"action":"decline","reason":"unclear","calls":[]}""") },
    var answer: (AiRequest) -> AiCompletion = { completed("""{"answer":"ok"}""") },
    enabled: Boolean = true,
    capUsd: String = "2.00",
    val consent: AskConsent = AskConsent.SERVER_FLAG,
    val quota: AskQuota = AskQuota(AskSeed.CLOCK)
) {
    val txns = InMemoryTransactionRepository().also { r -> runBlocking { AskSeed.transactions().forEach { r.save(it) } } }
    val analytics = AnalyticsService(InMemoryAnalyticsRepository(txns), AskSeed.CLOCK)
    val runner = AskToolRunner(analytics)
    val calls = InMemoryAiCallLogRepository()
    val provider = FakeAiProvider(prices = mapOf(FakeAiProvider.MODEL to AiPrice.usd("2.00", "10.00"))) { req ->
        if (req.system == AskPlanTask.SYSTEM_PROMPT) plan(req) else answer(req)
    }
    val gateway = AiGateway(
        if (enabled) listOf(AiRoute.of(AiFeature.ASK, provider, FakeAiProvider.MODEL)) else emptyList(),
        AiCostGuard(InMemoryAiSpendLedger(), Micros.ofUsd(capUsd), AskSeed.CLOCK), calls, clock = AskSeed.CLOCK
    )
    val service = AskService(gateway, runner, quota, AskSeed.CLOCK, consent)

    fun ask(question: String, user: java.util.UUID = AskSeed.OWNER) = runBlocking { service.ask(user, AskSeed.ZONE, question) }

    fun answered(question: String): AskAnswer = when (val o = ask(question)) {
        is AskOutcome.Answered -> o.answer
        is AskOutcome.Rejected -> throw AssertionError("rejected: ${o.why}")
    }

    fun rejected(question: String): AskRejection = when (val o = ask(question)) {
        is AskOutcome.Answered -> throw AssertionError("answered: ${o.answer.text}")
        is AskOutcome.Rejected -> o.why
    }

    val planRequests get() = provider.requests.filter { it.system == AskPlanTask.SYSTEM_PROMPT }
    val answerRequests get() = provider.requests.filter { it.system == AskAnswerTask.SYSTEM_PROMPT }

    companion object {
        fun completed(text: String) = AiCompletion.Completed(text, AiUsage(500, 100))

        fun period(preset: String = "last_month") = """{"preset":"$preset","month":null,"from":null,"to":null}"""

        fun call(tool: String, period: String = period(), category: String? = null, merchant: String? = null, bucket: String? = null, limit: Int? = null) =
            """{"tool":"$tool","period":$period,"category":${category?.let { "\"$it\"" } ?: "null"},"merchant":${merchant?.let { "\"$it\"" } ?: "null"},"bucket":${bucket?.let { "\"$it\"" } ?: "null"},"limit":${limit ?: "null"}}"""

        fun tools(vararg calls: String) = """{"action":"tools","reason":null,"calls":[${calls.joinToString(",")}]}"""

        fun answer(text: String) = completed(kotlinx.serialization.json.JsonObject(mapOf("answer" to kotlinx.serialization.json.JsonPrimitive(text))).toString())
    }
}
