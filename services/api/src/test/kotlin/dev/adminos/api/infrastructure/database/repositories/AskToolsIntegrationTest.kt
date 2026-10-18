package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ai.AiReading
import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.InMemoryAnalyticsRepository
import dev.adminos.api.domain.ask.AskPlan
import dev.adminos.api.domain.ask.AskPlanWire
import dev.adminos.api.domain.ask.AskResultWire
import dev.adminos.api.domain.ask.AskScope
import dev.adminos.api.domain.ask.AskToolOutcome
import dev.adminos.api.domain.ask.AskToolRunner
import dev.adminos.api.domain.ask.eval.AskEvalSet
import dev.adminos.api.domain.ask.eval.AskSeed
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AOS-712 on Postgres: the seeded synthetic DB gives the same tool results through the SQL analytics as in
 * memory, for every expected plan in the eval set, and the stranger's rows never reach the owner's results.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AskToolsIntegrationTest {

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    @Test
    fun `postgres and in-memory agree on every expected plan of the eval set`() = runBlocking<Unit> {
        val owner = PostgresTestDatabase.newUser()
        val stranger = PostgresTestDatabase.newUser()
        val pg = ExposedTransactionRepository()
        val mem = InMemoryTransactionRepository()
        for (t in AskSeed.transactions()) {
            val row = t.copy(userId = if (t.userId == AskSeed.OWNER) owner else stranger)
            pg.save(row); mem.save(row)
        }
        val sql = AskToolRunner(AnalyticsService(ExposedAnalyticsRepository(), AskSeed.CLOCK))
        val ref = AskToolRunner(AnalyticsService(InMemoryAnalyticsRepository(mem), AskSeed.CLOCK))
        val scope = AskScope(owner, AskSeed.ZONE, AskSeed.TODAY)
        var compared = 0
        for (q in AskEvalSet.load().questions) {
            val plan = (AskPlanWire.read(q.expected.toString()) as? AiReading.Valid)?.value as? AskPlan.Tools ?: continue
            val fromSql = plan.calls.map { (sql.run(scope, it) as AskToolOutcome.Ran).result }
            val fromMemory = plan.calls.map { (ref.run(scope, it) as AskToolOutcome.Ran).result }
            // The model's view formats every amount, so equal JSON means equal numbers whatever the decimal scale.
            assertEquals(AskResultWire.forModel(fromMemory), AskResultWire.forModel(fromSql), q.id)
            assertTrue("77,777" !in AskResultWire.forModel(fromSql).toString(), "${q.id} shows the stranger's order")
            compared++
        }
        assertTrue(compared >= 100, "compared $compared plans")
    }
}
