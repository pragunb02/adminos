package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ask.AskFeedback
import dev.adminos.api.domain.ask.AskSource
import dev.adminos.api.domain.ingestion.connection.randomTokenEncryptor
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.AskFeedbackTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-711 (V80) on Postgres: feedback round-trips, a vote replaces the earlier one, and the question is encrypted at rest. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AskFeedbackIntegrationTest {
    private val repo = ExposedAskFeedbackRepository(randomTokenEncryptor())
    private val at = Instant.parse("2026-10-18T14:30:00Z").truncatedTo(ChronoUnit.MILLIS)

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private fun feedback(user: UUID, id: UUID = UUID.randomUUID(), helpful: Boolean = true, plan: String? = """{"action":"decline","reason":"future","calls":[]}""") =
        AskFeedback(id, user, helpful, "Zepto pe pichle mahine kitna? <PHONE>", plan, AskSource.MODEL, "ask-v1", at)

    @Test
    fun `a vote round-trips, a second vote replaces it, and each user sees only their own`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        val other = PostgresTestDatabase.newUser()
        val f = feedback(me)
        repo.save(f)
        repo.save(f.copy(helpful = false, at = at.plusSeconds(5)))
        repo.save(feedback(other, plan = null))
        assertEquals(listOf(f.copy(helpful = false, at = at.plusSeconds(5))), repo.forUser(me))
        assertNull(repo.forUser(other).single().planJson)
    }

    @Test
    fun `the stored payload is ciphertext bound to its row`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        val a = feedback(me)
        val b = feedback(me)
        repo.save(a); repo.save(b)
        val raw = dbQuery { AskFeedbackTable.selectAll().where { AskFeedbackTable.askId eq a.askId }.single()[AskFeedbackTable.payload] }
        assertTrue(raw.startsWith("v2:") && "Zepto" !in raw && "decline" !in raw)
        dbQuery { AskFeedbackTable.update({ AskFeedbackTable.askId eq b.askId }) { it[payload] = raw } }
        assertFails("a payload copied to another row must not decrypt") { repo.forUser(me) }
    }

    @Test
    fun `the schema refuses a plaintext payload or an unknown source`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        val f = feedback(me)
        repo.save(f)
        assertFails { dbQuery { AskFeedbackTable.update({ AskFeedbackTable.askId eq f.askId }) { it[payload] = "Zepto in clear" } } }
        assertFails { dbQuery { AskFeedbackTable.update({ AskFeedbackTable.askId eq f.askId }) { it[answerSource] = "invented" } } }
    }
}
