package dev.adminos.api.config

import dev.adminos.api.di.buildAppModule
import dev.adminos.api.domain.financial.learning.CategoryModelRepository
import dev.adminos.api.domain.financial.learning.CategoryModelTrainingJob
import dev.adminos.api.domain.financial.learning.CategoryModelTrainingService
import dev.adminos.api.domain.financial.learning.InMemoryCategoryModelRepository
import dev.adminos.api.domain.ingestion.pipeline.IngestPipeline
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.infrastructure.database.repositories.ExposedCategoryModelRepository
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** AOS-707: learning from corrections is off by default (D-67) and needs no key when on. */
class LearnedCategorizationConfigTest {

    @Test
    fun `the flag is off unless it is true`() {
        assertFalse(LearnedCategorizationConfig.enabled { null })
        assertFalse(LearnedCategorizationConfig.enabled { " " })
        assertFalse(LearnedCategorizationConfig.enabled { "false" })
        assertTrue(LearnedCategorizationConfig.enabled { " TRUE " })
        assertFailsWith<IllegalArgumentException> { LearnedCategorizationConfig.enabled { "yes" } }
    }

    @Test
    fun `the job is registered only when on, and the service always answers`() {
        val off = koinApplication { modules(buildAppModule(useDatabase = false, env = { null })) }.koin
        assertTrue(off.getAll<ScheduledJob>().none { it.name.value == "category-model-training" })
        assertFalse(off.get<CategoryModelTrainingService>().isEnabled)
        assertIs<InMemoryCategoryModelRepository>(off.get<CategoryModelRepository>())

        val env = mapOf(LearnedCategorizationConfig.FLAG to "true")
        val on = koinApplication { modules(buildAppModule(useDatabase = false, env = env::get)) }.koin
        assertIs<CategoryModelTrainingJob>(on.getAll<ScheduledJob>().single { it.name.value == "category-model-training" })
        assertTrue(on.get<CategoryModelTrainingService>().isEnabled)
        assertEquals(off.getAll<ScheduledJob>().size + 1, on.getAll<ScheduledJob>().size)
        on.get<IngestPipeline>() // builds with the learned step

        val withDb = koinApplication { modules(buildAppModule(useDatabase = true, env = env::get)) }.koin
        assertIs<ExposedCategoryModelRepository>(withDb.get<CategoryModelRepository>())
    }

    @Test
    fun `the app module refuses to build with a malformed flag`() {
        assertFailsWith<IllegalArgumentException> { buildAppModule(useDatabase = false, env = mapOf(LearnedCategorizationConfig.FLAG to "1")::get) }
    }
}
