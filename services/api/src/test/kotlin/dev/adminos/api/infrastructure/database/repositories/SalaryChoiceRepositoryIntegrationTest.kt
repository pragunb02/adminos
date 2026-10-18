package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.forecast.InMemorySalaryChoiceRepository
import dev.adminos.api.domain.forecast.SalaryChoice
import dev.adminos.api.domain.forecast.SalaryChoiceKind
import dev.adminos.api.domain.forecast.SalaryChoiceRepository
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQuery
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/** V93 salary_choices on Postgres (AOS-722); the in-memory fake passes the same checks. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SalaryChoiceRepositoryIntegrationTest {

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private val at = Instant.parse("2026-10-18T10:00:00Z").truncatedTo(ChronoUnit.MICROS)

    private fun contract(repo: SalaryChoiceRepository) = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val other = PostgresTestDatabase.newUser()
        assertNull(repo.find(user))

        val confirmed = SalaryChoice("ACME TECHNOLOGIES", 1, BigDecimal("85000.00"), SalaryChoiceKind.CONFIRMED, at)
        assertEquals(confirmed, repo.put(user, confirmed))
        assertEquals(confirmed, repo.find(user))

        // A new choice replaces the old one: one row per user.
        val corrected = SalaryChoice(null, 31, BigDecimal("1000000000.00"), SalaryChoiceKind.CORRECTED, at.plusSeconds(60))
        repo.put(user, corrected)
        assertEquals(corrected, repo.find(user))

        // Another user's row is separate, and hostile text is a bound value read back verbatim.
        val hostile = "'; DROP TABLE salary_choices; -- <script>टाटा"
        val theirs = SalaryChoice(hostile, 15, BigDecimal("40000.50"), SalaryChoiceKind.CORRECTED, at)
        repo.put(other, theirs)
        assertEquals(theirs, repo.find(other))
        assertEquals(corrected, repo.find(user))
        assertNull(repo.find(UUID.randomUUID()))
    }

    @Test
    fun `a choice round-trips, replaces the user's earlier one and stays per user on Postgres`() = contract(ExposedSalaryChoiceRepository())

    @Test
    fun `the in-memory fake behaves the same`() = contract(InMemorySalaryChoiceRepository())

    @Test
    fun `the table refuses rows the domain would refuse`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val bad = listOf(
            "NULL, 0, 1.00, 'confirmed'", "NULL, 32, 1.00, 'confirmed'", "NULL, 1, 0, 'confirmed'", "NULL, 1, -1, 'confirmed'",
            "NULL, 1, 1.00, 'guessed'", "'', 1, 1.00, 'confirmed'", "' ACME', 1, 1.00, 'confirmed'", "'${"A".repeat(101)}', 1, 1.00, 'confirmed'",
            "NULL, 1, 1000000000.01, 'confirmed'",
        )
        for (values in bad) {
            assertFails(values) {
                dbQuery { TransactionManager.current().exec("INSERT INTO salary_choices (user_id, payer, pay_day, amount, kind) VALUES ('$user', $values)") }
            }
        }
        assertNull(ExposedSalaryChoiceRepository().find(user))
    }

    @Test
    fun `deleting the user deletes their choice`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val repo = ExposedSalaryChoiceRepository()
        repo.put(user, SalaryChoice("ACME", 1, BigDecimal("85000.00"), SalaryChoiceKind.CONFIRMED, at))
        dbQuery { TransactionManager.current().exec("DELETE FROM users WHERE id = '$user'") }
        assertNull(repo.find(user))
    }
}
