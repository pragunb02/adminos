package dev.adminos.api.config

import dev.adminos.api.di.aiDefinitions
import dev.adminos.api.di.buildAppModule
import dev.adminos.api.domain.ai.AiCallLogRepository
import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiProviderKind
import dev.adminos.api.domain.ai.AiSpendLedger
import dev.adminos.api.domain.ai.eval.AiEvalAnswers
import dev.adminos.api.domain.ai.eval.AiEvalRecord
import dev.adminos.api.domain.ai.eval.AiEvalRecords
import dev.adminos.api.domain.financial.InMemoryCategoryRuleRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.CategoryRuleRepository
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.financial.ai.AiCategorizationJob
import dev.adminos.api.domain.financial.ai.AiCategorizationService
import dev.adminos.api.domain.financial.ai.AiCategoryClient
import dev.adminos.api.domain.financial.ai.CategorizeMerchantTask
import dev.adminos.api.domain.financial.ai.PortAiCategoryClient
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.UserRepository
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.infrastructure.ai.OllamaProvider
import dev.adminos.api.infrastructure.database.repositories.ExposedAiCallLogRepository
import dev.adminos.api.infrastructure.database.repositories.ExposedAiCategoryCacheRepository
import dev.adminos.api.infrastructure.database.repositories.ExposedAiSpendLedger
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.net.URI
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-512 / AOS-702: every AI feature is off by default, and a half-configured setup refuses to start. */
class AiConfigTest {

    // Generated at runtime: never a literal key in the repo.
    private val key = "test-" + UUID.randomUUID()
    private val on = mapOf("AI_CATEGORIZATION_ENABLED" to "true", "ANTHROPIC_API_KEY" to key)
    private fun config(env: Map<String, String>) = AiConfig.from(env::get)
    private fun message(env: Map<String, String>) = assertFailsWith<IllegalArgumentException> { config(env) }.message.orEmpty()

    @Test
    fun `every feature is off unless enabled`() {
        assertEquals(emptyList(), AiConfig.from { null }.features)
        assertEquals(emptyList(), config(mapOf("AI_CATEGORIZATION_ENABLED" to "false", "ANTHROPIC_API_KEY" to key)).features)
        assertEquals(emptyList(), config(mapOf("ANTHROPIC_API_KEY" to key)).features, "a key alone does not turn anything on")
        assertNull(AiConfig.from { null }.anthropicApiKey, "an unused key isn't even read")
    }

    @Test
    fun `enabled without a key refuses to start and says what is missing`() {
        assertContains(message(mapOf("AI_CATEGORIZATION_ENABLED" to "true")), "ANTHROPIC_API_KEY")
        assertContains(message(mapOf("AI_CATEGORIZATION_ENABLED" to "true", "ANTHROPIC_API_KEY" to "  ")), "ANTHROPIC_API_KEY")
    }

    @Test
    fun `a flag that is not true or false refuses to start`() {
        assertContains(message(mapOf("AI_CATEGORIZATION_ENABLED" to "yes")), "AI_CATEGORIZATION_ENABLED")
    }

    @Test
    fun `on Anthropic it uses Claude Haiku 5_5 unless another model is named`() {
        assertEquals(AiFeatureSetting(AiFeature.CATEGORIZATION, AiProviderKind.ANTHROPIC, "claude-haiku-5-5"), config(on + ("AI_CATEGORIZATION_ENABLED" to "TRUE")).setting(AiFeature.CATEGORIZATION))
        assertEquals("claude-sonnet-5-5", config(on + ("AI_CATEGORIZATION_MODEL" to "claude-sonnet-5-5")).setting(AiFeature.CATEGORIZATION)?.model)
        assertContains(message(on + ("AI_CATEGORIZATION_MODEL" to "not a model!")), "AI_CATEGORIZATION_MODEL")
        assertContains(message(on + ("AI_CATEGORIZATION_PROVIDER" to "openai")), "AI_CATEGORIZATION_PROVIDER")
    }

    @Test
    fun `on Ollama it needs a model and a base URL, and no Anthropic key`() {
        val local = mapOf("AI_CATEGORIZATION_ENABLED" to "true", "AI_CATEGORIZATION_PROVIDER" to "Ollama")
        assertContains(message(local), "AI_CATEGORIZATION_MODEL")
        assertContains(message(local + ("AI_CATEGORIZATION_MODEL" to "qwen3:4b")), "OLLAMA_BASE_URL")
        val ok = config(local + mapOf("AI_CATEGORIZATION_MODEL" to "qwen3:4b", "OLLAMA_BASE_URL" to "http://ollama:11434/"))
        assertEquals(AiFeatureSetting(AiFeature.CATEGORIZATION, AiProviderKind.OLLAMA, "qwen3:4b"), ok.setting(AiFeature.CATEGORIZATION))
        assertEquals(OllamaSettings(URI("http://ollama:11434"), Duration.ofSeconds(120)), ok.ollama)
        assertNull(ok.anthropicApiKey)
        val withModel = local + ("AI_CATEGORIZATION_MODEL" to "qwen3:4b")
        assertEquals(Duration.ofSeconds(30), config(withModel + mapOf("OLLAMA_BASE_URL" to "http://127.0.0.1:11434", "OLLAMA_TIMEOUT_SECONDS" to "30")).ollama?.timeout)
        assertContains(message(withModel + ("OLLAMA_BASE_URL" to "ftp://ollama")), "OLLAMA_BASE_URL")
        assertContains(message(withModel + ("OLLAMA_BASE_URL" to "http://bad url")), "OLLAMA_BASE_URL")
        // Financial text never travels in cleartext off this machine or the compose network (D-91).
        assertContains(message(withModel + ("OLLAMA_BASE_URL" to "http://gpu-box.example.com:11434")), "https")
        assertContains(message(withModel + ("OLLAMA_BASE_URL" to "http://192.168.1.20:11434")), "https")
        assertEquals(URI("https://gpu-box.example.com"), config(withModel + ("OLLAMA_BASE_URL" to "https://gpu-box.example.com")).ollama?.baseUrl)
        assertEquals(URI("http://[::1]:11434"), config(withModel + ("OLLAMA_BASE_URL" to "http://[::1]:11434")).ollama?.baseUrl)
        assertEquals(URI("http://localhost:11434"), config(withModel + ("OLLAMA_BASE_URL" to "http://localhost:11434")).ollama?.baseUrl)
        assertContains(message(withModel + mapOf("OLLAMA_BASE_URL" to "http://ollama:11434", "OLLAMA_TIMEOUT_SECONDS" to "0")), "OLLAMA_TIMEOUT_SECONDS")
        assertContains(message(withModel + mapOf("OLLAMA_BASE_URL" to "http://ollama:11434", "OLLAMA_TIMEOUT_SECONDS" to "soon")), "OLLAMA_TIMEOUT_SECONDS")
    }

    @Test
    fun `the monthly cap is 2 dollars unless set, exact, and never negative`() {
        assertEquals(2_000_000, AiConfig.from { null }.monthlyCapMicros)
        assertEquals(0, config(mapOf("AI_MONTHLY_CAP_USD" to "0")).monthlyCapMicros)
        assertEquals(12_345_678, config(mapOf("AI_MONTHLY_CAP_USD" to "12.345678")).monthlyCapMicros)
        assertContains(message(mapOf("AI_MONTHLY_CAP_USD" to "1.0000001")), "6 decimal")
        assertContains(message(mapOf("AI_MONTHLY_CAP_USD" to "-1")), "AI_MONTHLY_CAP_USD")
        assertContains(message(mapOf("AI_MONTHLY_CAP_USD" to "lots")), "AI_MONTHLY_CAP_USD")
        assertContains(message(mapOf("AI_MONTHLY_CAP_USD" to "10000.01")), "AI_MONTHLY_CAP_USD")
    }

    @Test
    fun `the config checks its own invariants`() {
        val s = AiFeatureSetting(AiFeature.CATEGORIZATION, AiProviderKind.ANTHROPIC, "claude-haiku-5-5")
        assertFailsWith<IllegalArgumentException> { AiConfig(listOf(s, s), key, null, 0) }
        assertFailsWith<IllegalArgumentException> { AiConfig(listOf(s), null, null, 0) }
        assertFailsWith<IllegalArgumentException> { AiConfig(listOf(s.copy(provider = AiProviderKind.OLLAMA)), null, null, 0) }
        assertFailsWith<IllegalArgumentException> { AiConfig(emptyList(), null, null, -1) }
    }

    @Test
    fun `the key is never printed`() {
        val text = config(on).toString()
        assertFalse(text.contains(key))
        assertTrue(text.contains("***"))
        assertTrue(AiConfig.from { null }.toString().contains("unset"))
    }

    // ── Wiring ──

    @Test
    fun `the gateway always exists, and the categoriser's job and client only when enabled`() {
        val off = koinApplication { modules(buildAppModule(useDatabase = false, env = { null })) }.koin
        assertTrue(off.getAll<ScheduledJob>().none { it.name.value == "ai-categorization" })
        assertNull(off.getOrNull<AiCategorizationService>())
        assertNull(off.get<AiGateway>().route(AiFeature.CATEGORIZATION))

        val enabled = koinApplication { modules(buildAppModule(useDatabase = false, env = on::get)) }.koin
        assertIs<AiCategorizationJob>(enabled.getAll<ScheduledJob>().single { it.name.value == "ai-categorization" })
        assertIs<PortAiCategoryClient>(enabled.get<AiCategoryClient>())
        assertNotNull(enabled.get<AiCategorizationService>())
        assertEquals("claude-haiku-5-5", enabled.get<AiGateway>().route(AiFeature.CATEGORIZATION)?.model)
        assertEquals(off.getAll<ScheduledJob>().size + 1, enabled.getAll<ScheduledJob>().size)

        val withDb = koinApplication { modules(buildAppModule(useDatabase = true, env = on::get)) }.koin
        assertIs<ExposedAiCategoryCacheRepository>(withDb.get<dev.adminos.api.domain.financial.ai.AiCategoryCacheRepository>())
        assertIs<ExposedAiCallLogRepository>(withDb.get<AiCallLogRepository>())
        assertIs<ExposedAiSpendLedger>(withDb.get<AiSpendLedger>())
    }

    @Test
    fun `the app module refuses to build when enabled without a key`() {
        assertFailsWith<IllegalArgumentException> { buildAppModule(useDatabase = false, env = mapOf("AI_CATEGORIZATION_ENABLED" to "true")::get) }
    }

    @Test
    fun `a model with no passing eval record refuses to start (eval gate)`() {
        val e = assertFailsWith<IllegalStateException> { buildAppModule(useDatabase = false, env = (on + ("AI_CATEGORIZATION_MODEL" to "claude-sonnet-5-5"))::get) }
        assertContains(e.message.orEmpty(), "claude-sonnet-5-5")
        assertContains(e.message.orEmpty(), "no eval record")
        val local = mapOf("AI_CATEGORIZATION_ENABLED" to "true", "AI_CATEGORIZATION_PROVIDER" to "ollama",
            "AI_CATEGORIZATION_MODEL" to "tiny:1b", "OLLAMA_BASE_URL" to "http://127.0.0.1:11434")
        assertFailsWith<IllegalStateException> { buildAppModule(useDatabase = false, env = local::get) }
    }

    @Test
    fun `a model with an unknown price refuses to start`() {
        val e = assertFailsWith<IllegalArgumentException> { buildAppModule(useDatabase = false, env = (on + ("AI_CATEGORIZATION_MODEL" to "claude-sonnet-4-20250514"))::get) }
        assertContains(e.message.orEmpty(), "no known price")
    }

    @Test
    fun `an evaluated local model is wired to the Ollama adapter`() {
        val evals = AiEvalRecords(listOf(AiEvalRecord("categorization", "ollama", "tiny:1b", CategorizeMerchantTask.promptVersion,
            AiEvalAnswers.LIVE, "2026-10-17", 288, mapOf("accuracy" to 0.9), mapOf("accuracy" to 0.9))))
        val local = mapOf("AI_CATEGORIZATION_ENABLED" to "true", "AI_CATEGORIZATION_PROVIDER" to "ollama",
            "AI_CATEGORIZATION_MODEL" to "tiny:1b", "OLLAMA_BASE_URL" to "http://127.0.0.1:11434")
        val koin = koinApplication {
            modules(module {
                single<UserRepository> { InMemoryUserRepository() }
                single<TransactionRepository> { InMemoryTransactionRepository() }
                single<CategoryRuleRepository> { InMemoryCategoryRuleRepository() }
                aiDefinitions(useDatabase = false, env = local::get, evals = evals)
            })
        }.koin
        assertIs<OllamaProvider>(koin.get<AiGateway>().route(AiFeature.CATEGORIZATION)?.provider)
        assertNotNull(koin.get<AiCategorizationService>())
    }
}
