package dev.adminos.api.config

import dev.adminos.api.di.buildAppModule
import dev.adminos.api.domain.financial.ai.AiCategorizationJob
import dev.adminos.api.domain.financial.ai.AiCategorizationService
import dev.adminos.api.domain.financial.ai.CircuitBreakingAiClient
import dev.adminos.api.domain.financial.ai.AiCategoryClient
import dev.adminos.api.domain.scheduling.ScheduledJob
import org.koin.dsl.koinApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-512: the AI fallback is off by default, and a half-configured setup refuses to start. */
class AiCategorizationConfigTest {

    // Generated at runtime: never a literal key in the repo.
    private val key = "test-" + UUID.randomUUID()

    @Test
    fun `it is off unless enabled`() {
        assertNull(AiCategorizationConfig.from { null })
        assertNull(AiCategorizationConfig.from(mapOf("AI_CATEGORIZATION_ENABLED" to "false", "ANTHROPIC_API_KEY" to key)::get))
        assertNull(AiCategorizationConfig.from(mapOf("ANTHROPIC_API_KEY" to key)::get), "a key alone does not turn it on")
    }

    @Test
    fun `enabled without a key refuses to start and says what is missing`() {
        val e = assertFailsWith<IllegalArgumentException> { AiCategorizationConfig.from(mapOf("AI_CATEGORIZATION_ENABLED" to "true")::get) }
        assertTrue(e.message!!.contains("ANTHROPIC_API_KEY"))
        assertFailsWith<IllegalArgumentException> { AiCategorizationConfig.from(mapOf("AI_CATEGORIZATION_ENABLED" to "true", "ANTHROPIC_API_KEY" to "  ")::get) }
    }

    @Test
    fun `a flag that is not true or false refuses to start`() {
        assertFailsWith<IllegalArgumentException> { AiCategorizationConfig.from(mapOf("AI_CATEGORIZATION_ENABLED" to "yes")::get) }
    }

    @Test
    fun `enabled with a key uses Claude Haiku 5_5 unless another model is named`() {
        val on = mapOf("AI_CATEGORIZATION_ENABLED" to "TRUE", "ANTHROPIC_API_KEY" to key)
        assertEquals("claude-haiku-5-5", AiCategorizationConfig.from(on::get)?.model)
        assertEquals("claude-sonnet-5-5", AiCategorizationConfig.from((on + ("AI_CATEGORIZATION_MODEL" to "claude-sonnet-5-5"))::get)?.model)
        assertFailsWith<IllegalArgumentException> { AiCategorizationConfig.from((on + ("AI_CATEGORIZATION_MODEL" to "not a model!"))::get) }
        assertFailsWith<IllegalArgumentException> { AiCategorizationConfig("", "claude-haiku-5-5") }
    }

    @Test
    fun `the key is never printed`() {
        val text = AiCategorizationConfig(key).toString()
        assertFalse(text.contains(key))
        assertTrue(text.contains("***"))
    }

    @Test
    fun `the job and the client are wired only when enabled`() {
        val off = koinApplication { modules(buildAppModule(useDatabase = false, env = { null })) }.koin
        assertTrue(off.getAll<ScheduledJob>().none { it.name.value == "ai-categorization" })
        assertNull(off.getOrNull<AiCategorizationService>())

        val env = mapOf("AI_CATEGORIZATION_ENABLED" to "true", "ANTHROPIC_API_KEY" to key)
        val on = koinApplication { modules(buildAppModule(useDatabase = false, env = env::get)) }.koin
        assertIs<AiCategorizationJob>(on.getAll<ScheduledJob>().single { it.name.value == "ai-categorization" })
        assertIs<CircuitBreakingAiClient>(on.get<AiCategoryClient>())
        assertNotNull(on.get<AiCategorizationService>())
        assertEquals(off.getAll<ScheduledJob>().size + 1, on.getAll<ScheduledJob>().size)

        val withDb = koinApplication { modules(buildAppModule(useDatabase = true, env = env::get)) }.koin
        assertIs<dev.adminos.api.infrastructure.database.repositories.ExposedAiCategoryCacheRepository>(
            withDb.get<dev.adminos.api.domain.financial.ai.AiCategoryCacheRepository>())
    }

    @Test
    fun `the app module refuses to build when enabled without a key`() {
        assertFailsWith<IllegalArgumentException> { buildAppModule(useDatabase = false, env = mapOf("AI_CATEGORIZATION_ENABLED" to "true")::get) }
    }
}
