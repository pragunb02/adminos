package dev.adminos.api.domain.financial.ai

import dev.adminos.api.domain.ai.AiCompletion
import dev.adminos.api.domain.ai.AiCostGuard
import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiRoute
import dev.adminos.api.domain.ai.AiUsage
import dev.adminos.api.domain.ai.FakeAiProvider
import dev.adminos.api.domain.ai.InMemoryAiCallLogRepository
import dev.adminos.api.domain.ai.InMemoryAiSpendLedger
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** AOS-702: the categoriser's client maps the gateway's results onto AOS-512's three outcomes. */
class PortAiCategoryClientTest {

    private val user = UUID(0, 9)
    private val question = AiCategoryQuestion("Steam", PaymentMethod.CARD_CREDIT, AI_ALLOWED_CATEGORIES)
    private val provider = FakeAiProvider { AiCompletion.Completed("""{"category":"ENTERTAINMENT","confidence":0.9,"reason":"PC game store"}""", AiUsage(100, 20)) }

    private fun client(cap: Long = 1_000_000, routes: List<AiRoute> = listOf(AiRoute.of(AiFeature.CATEGORIZATION, provider, FakeAiProvider.MODEL))) =
        PortAiCategoryClient(AiGateway(routes, AiCostGuard(InMemoryAiSpendLedger(), cap), InMemoryAiCallLogRepository()))

    @Test
    fun `an answer is answered with its model`() = runBlocking<Unit> {
        assertEquals(AiCategoryResult.Answered(AiCategoryAnswer(TransactionCategory.ENTERTAINMENT, 0.9, "PC game store"), FakeAiProvider.MODEL),
            client().ask(user, question))
    }

    @Test
    fun `a withheld name is rejected, so it is cached and never sent`() = runBlocking<Unit> {
        assertEquals(AiCategoryResult.Rejected("withheld"), client().ask(user, question.copy(merchant = "ABCDE1234F Traders")))
        assertEquals(emptyList(), provider.requests)
    }

    @Test
    fun `feature off and cap reached are unavailable, so they are asked again later`() = runBlocking<Unit> {
        assertEquals(AiCategoryResult.Unavailable("feature_off"), client(routes = emptyList()).ask(user, question))
        assertEquals(AiCategoryResult.Unavailable("cap_reached"), client(cap = 0).ask(user, question))
        assertEquals(emptyList(), provider.requests)
    }
}
