package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.InMemoryCategoryRuleRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.financial.learning.LearningFixtures.CHAI
import dev.adminos.api.domain.financial.learning.LearningFixtures.KIRANAS
import dev.adminos.api.domain.financial.learning.LearningFixtures.PHARMACIES
import dev.adminos.api.domain.financial.learning.LearningFixtures.TUTORS
import dev.adminos.api.domain.financial.learning.LearningFixtures.clock
import dev.adminos.api.domain.financial.learning.LearningFixtures.txn
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.domain.scheduling.Schedule
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class CategoryModelTrainingJobTest {
    private val users = InMemoryUserRepository()
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun user() = User(id = UUID.randomUUID(), email = "u-${UUID.randomUUID()}@example.com", googleId = UUID.randomUUID().toString(), role = UserRole.OWNER)

    private fun job(txns: TransactionRepository): CategoryModelTrainingJob {
        val store = CategoryModelStore(InMemoryCategoryModelRepository(), clock)
        val categorizer = LearnedCategorizer(store, InMemoryCategoryRuleRepository(), txns, clock = clock)
        return CategoryModelTrainingJob(users, CategoryModelTrainingService(txns, store, categorizer, emptyList(), enabled = true, clock = clock), zone)
    }

    @Test
    fun `it runs nightly with a longer timeout`() {
        val j = job(InMemoryTransactionRepository())
        assertEquals("category-model-training", j.name.value)
        assertEquals(Schedule.Daily(LocalTime.of(3, 30), zone), j.schedule)
        assertEquals(Duration.ofMinutes(30), j.timeout)
    }

    @Test
    fun `it trains users with enough corrections, counts cold starts, and isolates a failing user`() = runBlocking<Unit> {
        val txns = InMemoryTransactionRepository()
        val ready = users.save(user())
        val cold = users.save(user())
        val broken = users.save(user())
        (PHARMACIES.map { it to TransactionCategory.HEALTH } + KIRANAS.map { it to TransactionCategory.SHOPPING } +
            TUTORS.map { it to TransactionCategory.EDUCATION } + CHAI.map { it to TransactionCategory.FOOD })
            .forEach { (name, c) -> txns.save(txn(ready.id, name, c, "user")) }
        txns.save(txn(ready.id, "Mahadev Medicos"))
        txns.save(txn(cold.id, "Sai Medicos", TransactionCategory.HEALTH, "user"))
        val failing = object : TransactionRepository by txns {
            override suspend fun findByUserId(
                userId: UUID, from: java.time.Instant?, to: java.time.Instant?, category: TransactionCategory?,
                type: dev.adminos.api.domain.financial.TransactionType?, accountId: UUID?, isRecurring: Boolean?, merchantSearch: String?,
                cursor: String?, limit: Int, nature: dev.adminos.api.domain.financial.TransactionNature?
            ): List<Transaction> {
                check(userId != broken.id) { "database unavailable" }
                return txns.findByUserId(userId, from, to, category, type, accountId, isRecurring, merchantSearch, cursor, limit, nature)
            }
        }
        val result = job(failing).run()
        assertEquals(3, result.counts["users"]); assertEquals(1, result.counts["failedUsers"])
        assertEquals(1, result.counts["trained"]); assertEquals(1, result.counts["coldStart"])
        assertEquals(1, (result.counts["applied"] ?: 0) + (result.counts["suggested"] ?: 0), result.toString())
    }
}
