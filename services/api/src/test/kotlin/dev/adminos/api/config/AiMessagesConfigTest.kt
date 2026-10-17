package dev.adminos.api.config

import dev.adminos.api.di.buildAppModule
import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiProviderKind
import dev.adminos.api.infrastructure.database.repositories.ExposedMessageProposalRepository
import dev.adminos.api.domain.ingestion.fallback.InMemoryMessageProposalRepository
import dev.adminos.api.domain.ingestion.fallback.MessageFallback
import dev.adminos.api.domain.ingestion.fallback.MessageFallbackJob
import dev.adminos.api.domain.ingestion.fallback.MessageProposalRepository
import dev.adminos.api.domain.ingestion.fallback.MessageReviewService
import dev.adminos.api.domain.ingestion.fallback.UnrecognisedMessageSink
import dev.adminos.api.domain.ingestion.fallback.FallbackOffer
import dev.adminos.api.domain.ingestion.fallback.MessageChannel
import dev.adminos.api.domain.ingestion.fallback.UnrecognisedMessage
import dev.adminos.api.domain.ingestion.fallback.templates.MessageTemplateRepository
import dev.adminos.api.domain.ingestion.fallback.templates.TemplateReader
import dev.adminos.api.infrastructure.database.repositories.ExposedMessageTemplateRepository
import kotlinx.coroutines.runBlocking
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.infrastructure.ai.AnthropicProvider
import dev.adminos.api.infrastructure.ai.OllamaProvider
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** AOS-705: AI_MESSAGES_* settings and wiring. Off by default; local (Ollama) unless cloud is chosen explicitly. */
class AiMessagesConfigTest {
    private val local = mapOf("AI_MESSAGES_ENABLED" to "true", "AI_MESSAGES_MODEL" to "qwen3:4b", "OLLAMA_BASE_URL" to "http://127.0.0.1:11434")
    private val cloud = mapOf("AI_MESSAGES_ENABLED" to "true", "AI_MESSAGES_PROVIDER" to "anthropic", "ANTHROPIC_API_KEY" to "test-key-not-real")

    private fun message() = UnrecognisedMessage(java.util.UUID.randomUUID(), MessageChannel.SMS, "ref", java.time.Instant.now(), java.time.ZoneOffset.UTC, null,
        "Card XX4321 charged Rs.249.00 at ZEPTO")
    private fun message(env: Map<String, String>) =assertFailsWith<IllegalArgumentException> { AiConfig.from(env::get) }.message.orEmpty()
    private fun koin(env: Map<String, String>, useDatabase: Boolean = false) = koinApplication { modules(buildAppModule(useDatabase = useDatabase, env = env::get)) }.koin

    @Test
    fun `message reading is off unless enabled and defaults to a local model`() {
        assertNull(AiConfig.from { null }.setting(AiFeature.MESSAGES))
        assertEquals(AiFeatureSetting(AiFeature.MESSAGES, AiProviderKind.OLLAMA, "qwen3:4b"), AiConfig.from(local::get).setting(AiFeature.MESSAGES))
        assertEquals(AiProviderKind.OLLAMA, AiFeature.MESSAGES.defaultProvider)
        assertEquals(AiProviderKind.ANTHROPIC, AiFeature.CATEGORIZATION.defaultProvider)
        assertEquals("AI_MESSAGES", AiFeature.MESSAGES.envPrefix)
    }

    @Test
    fun `local reading needs a model and an Ollama address, cloud needs a key`() {
        assertContains(message(mapOf("AI_MESSAGES_ENABLED" to "true")), "AI_MESSAGES_MODEL")
        assertContains(message(mapOf("AI_MESSAGES_ENABLED" to "true", "AI_MESSAGES_MODEL" to "qwen3:4b")), "OLLAMA_BASE_URL")
        assertContains(message(cloud - "ANTHROPIC_API_KEY"), "ANTHROPIC_API_KEY")
        assertEquals(AiFeatureSetting(AiFeature.MESSAGES, AiProviderKind.ANTHROPIC, "claude-haiku-5-5"), AiConfig.from(cloud::get).setting(AiFeature.MESSAGES))
    }

    @Test
    fun `off - review is still there, nothing is read and no job runs`() {
        val off = koin(emptyMap())
        assertNull(off.getOrNull<MessageFallback>())
        assertIs<TemplateReader>(off.get<UnrecognisedMessageSink>(), "approved templates read even with AI off (AOS-706)")
        assertEquals(FallbackOffer.Off, runBlocking { off.get<UnrecognisedMessageSink>().offer(message()) })
        assertNotNull(off.get<MessageReviewService>())
        assertIs<InMemoryMessageProposalRepository>(off.get<MessageProposalRepository>())
        assertTrue(off.getAll<ScheduledJob>().none { it.name.value == "ai-message-reading" })
        assertNull(off.get<AiGateway>().route(AiFeature.MESSAGES))
    }

    @Test
    fun `on - unrecognised messages go to the fallback and a job reads them`() {
        val on = koin(local)
        on.get<MessageFallback>()
        assertEquals(FallbackOffer.Queued, runBlocking { on.get<UnrecognisedMessageSink>().offer(message()) }, "templates first, then the AI reader")
        assertIs<ExposedMessageTemplateRepository>(koin(local, useDatabase = true).get<MessageTemplateRepository>())
        assertIs<MessageFallbackJob>(on.getAll<ScheduledJob>().single { it.name.value == "ai-message-reading" })
        assertIs<OllamaProvider>(on.get<AiGateway>().route(AiFeature.MESSAGES)?.provider)
        assertIs<AnthropicProvider>(koin(cloud).get<AiGateway>().route(AiFeature.MESSAGES)?.provider)
        assertIs<ExposedMessageProposalRepository>(koin(local, useDatabase = true).get<MessageProposalRepository>())
    }

    @Test
    fun `an unevaluated model refuses to start`() {
        val e = assertFailsWith<IllegalStateException> { buildAppModule(useDatabase = false, env = (local + ("AI_MESSAGES_MODEL" to "tiny:1b"))::get) }
        assertContains(e.message.orEmpty(), "messages on ollama with tiny:1b")
    }

    @Test
    fun `the daily limit is configurable and checked`() {
        koin(local + ("AI_MESSAGES_DAILY_LIMIT" to "5"))
        koin(local + ("AI_MESSAGES_DAILY_LIMIT" to " "))
        listOf("-1", "lots", "10001").forEach {
            assertContains(assertFailsWith<IllegalArgumentException> { koin(local + ("AI_MESSAGES_DAILY_LIMIT" to it)).get<MessageFallback>() }.message.orEmpty(), "AI_MESSAGES_DAILY_LIMIT")
        }
    }
}
