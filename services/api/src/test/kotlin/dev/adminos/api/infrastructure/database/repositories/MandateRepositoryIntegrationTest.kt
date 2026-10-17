package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.mandates.*
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** V22 mandates on Postgres (AOS-502): every field round-trips; one row per (user, UMN), case-insensitive. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MandateRepositoryIntegrationTest {
    private val repo = ExposedMandateRepository()

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    @Test
    fun `a mandate round-trips, is found by UMN in any case, and the UMN is unique per user`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val m = repo.save(Mandate(userId = user, rail = MandateRail.NACH, payee = "BAJAJ FINANCE LTD", umn = "HDFC7000000012345",
            amount = BigDecimal("5400.00"), frequency = MandateFrequency.MONTHLY, status = MandateStatus.ACTIVE,
            statusChangedAt = Instant.parse("2026-10-01T05:00:00Z"), createdOn = LocalDate.of(2026, 10, 1), validUntil = LocalDate.of(2030, 3, 5),
            nextDebitOn = LocalDate.of(2026, 11, 5), nextDebitAmount = BigDecimal("5400.00"), bank = "icici", lastMessageRef = "fp-1",
            createdAt = Instant.parse("2026-10-01T05:00:00Z"), updatedAt = Instant.parse("2026-10-01T05:00:00Z")))
        val back = repo.findByUmn(user, "hdfc7000000012345")!!
        assertEquals(m.copy(updatedAt = back.updatedAt), back)

        repo.update(back.copy(status = MandateStatus.PAUSED, lastDebitAt = Instant.parse("2026-11-05T04:00:00Z"), lastDebitAmount = BigDecimal("5400.00")))
        val updated = repo.findByUserId(user).single()
        assertEquals(MandateStatus.PAUSED, updated.status)
        assertEquals(BigDecimal("5400.00"), updated.lastDebitAmount)

        assertFailsWith<ExposedSQLException> {
            repo.save(m.copy(id = java.util.UUID.randomUUID(), umn = "hdfc7000000012345"))
        }
        assertEquals(1, repo.findByUserId(user).size)
    }
}
