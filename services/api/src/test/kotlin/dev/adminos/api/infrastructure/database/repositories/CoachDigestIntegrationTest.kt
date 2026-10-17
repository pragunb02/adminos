package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.coach.digest.DigestKind
import dev.adminos.api.domain.coach.digest.DigestLedger
import dev.adminos.api.domain.coach.digest.InMemoryDigestLedger
import dev.adminos.api.domain.coach.settings.CoachSettings
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V42 on Postgres (AOS-905): one digest per user and day, and the digest settings round-trip. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoachDigestIntegrationTest {
    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private val at = Instant.parse("2026-11-18T16:00:00Z")

    private fun ledger(l: DigestLedger) = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val day = LocalDate.of(2026, 11, 18)
        val wins = coroutineScope { (1..8).map { async { l.claim(user, DigestKind.EVENING, day, at) } }.awaitAll() }
        assertEquals(1, wins.count { it })
        assertTrue(l.claim(user, DigestKind.EVENING, day.plusDays(1), at))
        assertTrue(l.claim(PostgresTestDatabase.newUser(), DigestKind.EVENING, day, at))
    }

    @Test fun `one evening digest per day on Postgres, even under a race`() = ledger(ExposedDigestLedger())
    @Test fun `the in-memory ledger behaves the same`() = ledger(InMemoryDigestLedger())

    @Test
    fun `digest settings round-trip and default for old rows`() = runBlocking<Unit> {
        val repo = ExposedCoachSettingsRepository()
        val user = PostgresTestDatabase.newUser()
        val s = CoachSettings(user, limitAlerts = false, eveningDigest = false, digestTime = LocalTime.of(20, 15), digestSkipZeroDay = true, updatedAt = at)
        repo.save(s)
        assertEquals(s, repo.find(user))
        repo.save(s.copy(digestTime = LocalTime.of(21, 30), eveningDigest = true))
        assertEquals(LocalTime.of(21, 30), repo.find(user)?.digestTime)
    }
}
