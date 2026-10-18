package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.review.GoldenCandidate
import dev.adminos.api.domain.financial.review.GoldenCandidateRepository
import dev.adminos.api.domain.financial.review.InMemoryGoldenCandidateRepository
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-708 on Postgres: the local golden set behaves like the in-memory one (Liskov). */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoldenCandidateIntegrationTest {

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private val at = Instant.parse("2026-10-17T10:00:00Z")

    private fun row(userId: UUID, raw: String, method: PaymentMethod? = PaymentMethod.UPI, minutes: Long = 0) =
        GoldenCandidate(userId = userId, merchantRaw = raw, paymentMethod = method, direction = TransactionType.DEBIT,
            category = TransactionCategory.HEALTH, createdAt = at.plusSeconds(minutes * 60))

    private suspend fun behaves(repo: GoldenCandidateRepository, me: UUID, other: UUID) {
        val first = row(me, "UPI-SAI MEDICOS-PAYMENT", minutes = 2)
        val second = row(me, "OM MEDICOS", method = null, minutes = 1)
        assertTrue(repo.add(first))
        assertTrue(repo.add(second))
        assertFalse(repo.add(row(me, "UPI-SAI MEDICOS-PAYMENT")), "the same row twice")
        assertFalse(repo.add(row(me, "OM MEDICOS", method = null)), "the same row twice, without a method")
        assertTrue(repo.add(row(other, "UPI-SAI MEDICOS-PAYMENT")), "another user's set is separate")
        assertEquals(listOf(second, first), repo.list(me), "oldest first")
        assertFalse(repo.delete(other, first.id), "only the owner removes")
        assertTrue(repo.delete(me, first.id))
        assertEquals(listOf(second), repo.list(me))
    }

    @Test
    fun `the local golden set works the same on Postgres and in memory`() = runBlocking<Unit> {
        behaves(ExposedGoldenCandidateRepository(), PostgresTestDatabase.newUser(), PostgresTestDatabase.newUser())
        behaves(InMemoryGoldenCandidateRepository(), UUID.randomUUID(), UUID.randomUUID())
    }
}
