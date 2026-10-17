package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.coach.goals.DuplicateGoalException
import dev.adminos.api.domain.coach.goals.Goal
import dev.adminos.api.domain.coach.goals.GoalKind
import dev.adminos.api.domain.coach.goals.GoalRepository
import dev.adminos.api.domain.coach.goals.InMemoryGoalRepository
import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.coach.limits.LimitPeriod
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V37 coach_goals on Postgres (AOS-907); the fake passes the same contract. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoachGoalsIntegrationTest {
    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private fun contract(repo: GoalRepository) = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val cut = Goal(userId = user, lens = LensId("quick_commerce"), kind = GoalKind.CUT_PERCENT, value = BigDecimal("30.00"), period = LimitPeriod.WEEKLY,
            baseline = BigDecimal("684.78"), target = BigDecimal("479.00"), startedOn = LocalDate.of(2026, 11, 18), createdAt = Instant.parse("2026-11-18T06:30:00Z"))
        val cap = cut.copy(id = UUID.randomUUID(), lens = LensId("travel"), kind = GoalKind.CAP_AMOUNT, value = BigDecimal("5000.00"), baseline = null,
            target = BigDecimal("5000.00"), period = LimitPeriod.MONTHLY, createdAt = cut.createdAt.plusSeconds(1))
        repo.save(cut); repo.save(cap)
        assertFailsWith<DuplicateGoalException> { repo.save(cut.copy(id = UUID.randomUUID())) }
        assertEquals(listOf(cut, cap), repo.findByUser(user))
        assertFalse(repo.delete(PostgresTestDatabase.newUser(), cut.id))
        assertTrue(repo.delete(user, cut.id))
        assertEquals(listOf(cap), repo.findByUser(user))
    }

    @Test fun `goals on Postgres`() = contract(ExposedGoalRepository())
    @Test fun `goals in memory behave the same`() = contract(InMemoryGoalRepository())
}
