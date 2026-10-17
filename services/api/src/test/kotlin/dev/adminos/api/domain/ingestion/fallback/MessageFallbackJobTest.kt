package dev.adminos.api.domain.ingestion.fallback

import dev.adminos.api.domain.ai.AiCompletion
import dev.adminos.api.domain.ai.AiCostGuard
import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiPrice
import dev.adminos.api.domain.ai.AiProviderKind
import dev.adminos.api.domain.ai.AiRoute
import dev.adminos.api.domain.ai.AiUsage
import dev.adminos.api.domain.ai.FakeAiProvider
import dev.adminos.api.domain.ai.InMemoryAiCallLogRepository
import dev.adminos.api.domain.ai.InMemoryAiSpendLedger
import dev.adminos.api.domain.scheduling.RetryPolicy
import dev.adminos.api.domain.scheduling.Schedule
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageFallbackJobTest {
    @Test
    fun `each minute the job reads up to its share and reports counts only`() = runBlocking<Unit> {
        val provider = FakeAiProvider(AiProviderKind.OLLAMA, mapOf("qwen3:4b" to AiPrice.FREE)) {
            AiCompletion.Completed("""{"kind":"not_a_transaction","direction":null,"amount":null,"accountLast4":null,"merchant":null,"date":null}""", AiUsage(1, 1))
        }
        val gateway = AiGateway(listOf(AiRoute.of(AiFeature.MESSAGES, provider, "qwen3:4b")), AiCostGuard(InMemoryAiSpendLedger(), 0), InMemoryAiCallLogRepository())
        val fallback = MessageFallback(gateway, InMemoryMessageProposalRepository())
        val user = UUID.randomUUID()
        (1..3).forEach { fallback.offer(UnrecognisedMessage(user, MessageChannel.SMS, "r$it", Instant.now(), ZoneOffset.UTC, null, "Get Rs 100 cashback")) }
        val job = MessageFallbackJob(fallback, perRun = 2)
        assertEquals("ai-message-reading", job.name.value)
        assertEquals(Schedule.Every(Duration.ofMinutes(1)), job.schedule)
        assertEquals(RetryPolicy(1, Duration.ZERO), job.retry)
        assertEquals(Duration.ofMinutes(15), job.timeout)
        val result = job.run()
        assertEquals(2, result.counts["notTransactions"])
        assertEquals(1, result.counts["waiting"])
        assertEquals(0, result.counts["proposed"])
    }
}
