package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.coach.lenses.InMemoryLensOverrideRepository
import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.coach.lenses.LensOverride
import dev.adminos.api.domain.coach.lenses.LensOverrideRepository
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V31 lens_overrides on Postgres (AOS-901); the in-memory fake passes the same checks. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LensOverrideRepositoryIntegrationTest {

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private val at = Instant.parse("2026-10-17T10:00:00Z").truncatedTo(ChronoUnit.MICROS)

    private fun contract(repo: LensOverrideRepository) = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val other = PostgresTestDatabase.newUser()
        val nights = LensOverride.of(user, LensId("nights_out"), "Rohit Sharma", include = true, at = at)
        repo.upsert(nights)
        repo.upsert(LensOverride.of(user, LensId("shopping"), "Amazon", include = false, at = at))
        repo.upsert(LensOverride.of(other, LensId("shopping"), "Amazon", include = true, at = at))
        // Same (user, lens, merchant) in another case replaces the choice.
        repo.upsert(LensOverride.of(user, LensId("shopping"), "AMAZON", include = true, at = at.plusSeconds(60)))

        val mine = repo.findByUser(user)
        assertEquals(listOf("nights_out" to "rohit sharma", "shopping" to "amazon"), mine.map { it.lensId.value to it.merchantKey })
        assertEquals(nights, mine.first())
        assertTrue(mine.last().include)
        assertEquals("AMAZON", mine.last().merchant)

        assertTrue(repo.delete(user, LensId("shopping"), "amazon"))
        assertFalse(repo.delete(user, LensId("shopping"), "amazon"))
        assertEquals(1, repo.findByUser(user).size)
        assertEquals(1, repo.findByUser(other).size)

        // Hostile text is a bound value, stored and read back verbatim.
        val hostile = "'; DROP TABLE lens_overrides; -- <script>"
        repo.upsert(LensOverride.of(other, LensId("travel"), hostile, include = false, at = at))
        assertEquals(hostile, repo.findByUser(other).first { it.lensId == LensId("travel") }.merchant)
    }

    @Test
    fun `overrides round-trip, replace by key and delete on Postgres`() = contract(ExposedLensOverrideRepository())

    @Test
    fun `the in-memory fake behaves the same`() = contract(InMemoryLensOverrideRepository())
}
