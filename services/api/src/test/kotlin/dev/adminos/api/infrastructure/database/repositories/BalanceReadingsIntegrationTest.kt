package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.balances.BalanceAccount
import dev.adminos.api.domain.balances.BalanceObservation
import dev.adminos.api.domain.balances.BalanceReading
import dev.adminos.api.domain.balances.BalanceRecordResult
import dev.adminos.api.domain.balances.BalanceRules
import dev.adminos.api.domain.balances.BalanceService
import dev.adminos.api.domain.balances.BalanceSource
import dev.adminos.api.domain.ingestion.connection.randomTokenEncryptor
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.BalanceReadingsTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-720 (V92) on Postgres: readings round-trip, the latest wins by time, duplicates and old rows go, the figure is ciphertext. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BalanceReadingsIntegrationTest {
    private val repo = ExposedBalanceRepository(randomTokenEncryptor())
    private val now = Instant.parse("2026-10-20T04:30:00Z")
    private val service = BalanceService(repo, Clock.fixed(now, ZoneOffset.UTC))
    private val ist = ZoneId.of("Asia/Kolkata")

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private fun ref(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun obs(user: UUID, amount: String, at: String, hint: String = "1234", bank: String? = "hdfc", source: BalanceSource = BalanceSource.SMS, key: String = "$user|$amount|$at|$hint") =
        BalanceObservation(user, bank, hint, BigDecimal(amount), Instant.parse(at), source, ref(key))

    @Test
    fun `readings round-trip and the newest observed is the latest, whatever the arrival order`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        assertEquals(BalanceRecordResult.Saved, service.record(obs(me, "-1250.50", "2026-10-19T05:00:00Z")))
        service.record(obs(me, "9999.00", "2026-10-15T05:00:00Z"))
        service.record(obs(me, "300.00", "2026-10-19T05:00:00Z", source = BalanceSource.STATEMENT))
        service.record(obs(me, "42.00", "2026-10-18T05:00:00Z", hint = "5678", bank = null))
        val readings = repo.forUser(me)
        assertEquals(4, readings.size)
        val latest = service.overview(me, ist).accounts
        assertEquals(listOf(BalanceAccount("hdfc", "1234") to BigDecimal("300.00"), BalanceAccount(null, "5678") to BigDecimal("42.00")),
            latest.map { it.account to it.reading.amount }, "a statement at the same moment beats the SMS")
        assertEquals(BigDecimal("-1250.50"), readings.first { it.source == BalanceSource.SMS && it.observedAt == Instant.parse("2026-10-19T05:00:00Z") }.amount)
    }

    @Test
    fun `the same input records once, and each user sees only their own`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        val other = PostgresTestDatabase.newUser()
        service.record(obs(me, "10.00", "2026-10-19T05:00:00Z", key = "same"))
        assertEquals(BalanceRecordResult.Duplicate, service.record(obs(me, "10.00", "2026-10-19T05:00:00Z", key = "same")))
        assertEquals(BalanceRecordResult.Saved, service.record(obs(other, "10.00", "2026-10-19T05:00:00Z", key = "same")), "another user's same ref is theirs")
        assertEquals(1, repo.forUser(me).size)
        assertEquals(1, repo.forUser(other).size)
    }

    @Test
    fun `only the newest readings of an account are kept`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        repeat(BalanceRules.KEEP_PER_ACCOUNT + 3) { i -> service.record(obs(me, "$i.00", Instant.parse("2026-10-01T00:00:00Z").plusSeconds(3600L * i).toString())) }
        service.record(obs(me, "1.00", "2026-10-01T00:00:00Z", hint = "9999"))
        val kept = repo.forUser(me)
        assertEquals(BalanceRules.KEEP_PER_ACCOUNT + 1, kept.size)
        assertEquals((3 until BalanceRules.KEEP_PER_ACCOUNT + 3).map { BigDecimal("$it.00") }.toSet(), kept.filter { it.account.last4 == "1234" }.map { it.amount }.toSet())
    }

    @Test
    fun `the stored figure is ciphertext bound to its row`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        service.record(obs(me, "43210.98", "2026-10-19T05:00:00Z"))
        service.record(obs(me, "1.00", "2026-10-18T05:00:00Z"))
        val rows = dbQuery { BalanceReadingsTable.selectAll().where { BalanceReadingsTable.userId eq me }.map { it[BalanceReadingsTable.id] to it[BalanceReadingsTable.amount] } }
        assertTrue(rows.all { (_, a) -> a.startsWith("v2:") && "43210" !in a })
        val (first, second) = rows
        dbQuery { BalanceReadingsTable.update({ BalanceReadingsTable.id eq second.first }) { it[amount] = first.second } }
        assertFails("a figure copied to another row must not decrypt") { repo.forUser(me) }
        dbQuery { BalanceReadingsTable.deleteWhere { BalanceReadingsTable.id eq second.first } }
    }

    @Test
    fun `the schema refuses plaintext figures, bad accounts and unknown sources`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        service.record(obs(me, "5.00", "2026-10-19T05:00:00Z"))
        val id = repo.forUser(me).single().id
        assertFails { dbQuery { BalanceReadingsTable.update({ BalanceReadingsTable.id eq id }) { it[amount] = "5.00" } } }
        assertFails { dbQuery { BalanceReadingsTable.update({ BalanceReadingsTable.id eq id }) { it[accountLast4] = "12a4" } } }
        assertFails { dbQuery { BalanceReadingsTable.update({ BalanceReadingsTable.id eq id }) { it[sourceKind] = "gmail" } } }
        assertFails { dbQuery { BalanceReadingsTable.update({ BalanceReadingsTable.id eq id }) { it[bankCode] = "HDFC BANK" } } }
        assertFalse(repo.forUser(me).single().toString().contains("5.00"))
    }

    @Test
    fun `deleting a user deletes their balances`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        service.record(obs(me, "5.00", "2026-10-19T05:00:00Z"))
        dbQuery { dev.adminos.api.infrastructure.database.tables.UsersTable.deleteWhere { dev.adminos.api.infrastructure.database.tables.UsersTable.id eq me } }
        assertEquals(emptyList<BalanceReading>(), repo.forUser(me))
    }
}
