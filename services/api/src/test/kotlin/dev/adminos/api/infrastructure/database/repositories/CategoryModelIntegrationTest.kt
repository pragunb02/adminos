package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.learning.CategoryModelCodec
import dev.adminos.api.domain.financial.learning.CategoryModelRepository
import dev.adminos.api.domain.financial.learning.CategoryModelStore
import dev.adminos.api.domain.financial.learning.CategoryModelTrainingService
import dev.adminos.api.domain.financial.learning.InMemoryCategoryModelRepository
import dev.adminos.api.domain.financial.learning.LearnedCategorizer
import dev.adminos.api.domain.financial.learning.LearningFixtures
import dev.adminos.api.domain.financial.learning.LoadedModel
import dev.adminos.api.domain.financial.learning.ModelMode
import dev.adminos.api.domain.financial.learning.ModelState
import dev.adminos.api.domain.financial.learning.StoredCategoryModel
import dev.adminos.api.domain.financial.learning.TrainingOutcome
import dev.adminos.api.domain.financial.learning.TrainingSummary
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.CategoryModelsTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-707 on Postgres: the `category_models` table, and a training run whose model is stored and used. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CategoryModelIntegrationTest {

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private val at = Instant.parse("2026-10-17T10:00:00Z")
    private val encoded = CategoryModelCodec.encode(LearningFixtures.trainedModel().model)

    private fun model(userId: UUID, mode: ModelMode = ModelMode.APPLY, precision: Double? = 0.95) = StoredCategoryModel(
        userId, "softmax-v1", 1, encoded.bytes, encoded.checksum, mode, TrainingSummary(320, 40, 10, 40, precision, 0.07, 1.3), at)

    /** The same checks for both implementations (Liskov). */
    private suspend fun roundTrips(repo: CategoryModelRepository, userId: UUID) {
        assertNull(repo.find(userId)); assertNull(repo.findChecksum(userId))
        repo.save(model(userId))
        assertEquals(model(userId), repo.find(userId))
        assertEquals(encoded.checksum, repo.findChecksum(userId))
        repo.save(model(userId, ModelMode.SUGGEST, precision = null))
        assertEquals(model(userId, ModelMode.SUGGEST, precision = null), repo.find(userId), "a retrain replaces the row")
        assertTrue(repo.delete(userId))
        assertFalse(repo.delete(userId))
        assertNull(repo.find(userId))
    }

    @Test
    fun `models round-trip on Postgres like in memory`() = runBlocking<Unit> {
        roundTrips(ExposedCategoryModelRepository(), PostgresTestDatabase.newUser())
        roundTrips(InMemoryCategoryModelRepository(), UUID.randomUUID())
    }

    @Test
    fun `a training run on Postgres stores a model the pipeline can use, and a damaged row is reported`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val txns = ExposedTransactionRepository()
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        LearningFixtures.localShopExamples(perKind = 6).forEach { e ->
            txns.save(LearningFixtures.txn(userId, e.input.merchantName, e.label, "user", at = now.minusSeconds(3600)))
        }
        val repo = ExposedCategoryModelRepository()
        val store = CategoryModelStore(repo)
        val categorizer = LearnedCategorizer(store, ExposedCategoryRuleRepository(), txns)
        val service = CategoryModelTrainingService(txns, store, categorizer, emptyList(), enabled = true)

        val trained = assertIs<TrainingOutcome.Trained>(service.train(userId)).model
        assertEquals(24, trained.summary.corrections)
        val fresh = CategoryModelStore(repo)
        val loaded = assertIs<LoadedModel.Ready>(fresh.load(userId))
        assertEquals(TransactionCategory.HEALTH, loaded.model.predict(LearningFixtures.input("Mahadev Medicos")).category)

        dbQuery { CategoryModelsTable.update({ CategoryModelsTable.userId eq userId }) { it[blob] = "junk".toByteArray() } }
        val afterDamage = CategoryModelTrainingService(txns, CategoryModelStore(repo), categorizer, emptyList(), enabled = true)
        assertEquals(ModelState.CORRUPT, afterDamage.status(userId).state)
    }
}
