package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.fallback.MessageChannel
import dev.adminos.api.domain.ingestion.fallback.MessageProposal
import dev.adminos.api.domain.ingestion.fallback.templates.InMemoryMessageTemplateRepository
import dev.adminos.api.domain.ingestion.fallback.templates.MessageShape
import dev.adminos.api.domain.ingestion.fallback.templates.MessageTemplate
import dev.adminos.api.domain.ingestion.fallback.templates.MessageTemplateRepository
import dev.adminos.api.domain.ingestion.fallback.templates.TemplateStatus
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** AOS-706 on Postgres: templates behave like their in-memory twin; shapes with digits can't be stored at all. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageTemplateRepositoryIntegrationTest {

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private val at = Instant.parse("2026-10-16T05:00:00Z")
    private val shape = MessageShape("Card XX<ACCT> charged Rs.<AMT> at <M> on <DATE>.")

    private fun template(user: UUID, bank: String? = "fedbnk", status: TemplateStatus = TemplateStatus.OBSERVING, seen: Int = 1, direction: IngestDirection = IngestDirection.DEBIT) =
        MessageTemplate(userId = user, channel = MessageChannel.SMS, bankCode = bank, direction = direction, shape = shape, status = status, seen = seen, createdAt = at)

    private suspend fun contract(repo: MessageTemplateRepository, me: UUID, other: UUID) {
        val a = template(me)
        val nullBank = template(me, bank = null)
        val credit = template(me, direction = IngestDirection.CREDIT)
        listOf(a, nullBank, credit, template(other)).forEach { repo.save(it) }
        assertEquals(listOf(a), repo.forFormat(me, MessageChannel.SMS, "fedbnk", IngestDirection.DEBIT))
        assertEquals(listOf(nullBank), repo.forFormat(me, MessageChannel.SMS, null, IngestDirection.DEBIT), "a null bank is its own sender")

        val proposed = a.copy(status = TemplateStatus.PROPOSED, seen = 3, updatedAt = at.plusSeconds(1))
        assertTrue(repo.update(proposed))
        assertEquals(false, repo.update(proposed.copy(userId = other)), "another user's id doesn't update")
        assertEquals(listOf(proposed), repo.proposed(me, 10))
        assertEquals(false, repo.decide(other, a.id, TemplateStatus.APPROVED, at))
        assertTrue(repo.decide(me, a.id, TemplateStatus.APPROVED, at.plusSeconds(2)))
        assertEquals(false, repo.decide(me, a.id, TemplateStatus.REJECTED, at), "decided once")
        assertFailsWith<IllegalArgumentException> { repo.decide(me, a.id, TemplateStatus.PROPOSED, at) }
        repo.hit(a.id)
        repo.hit(a.id)
        val approved = repo.approved(me, MessageChannel.SMS, "fedbnk").single()
        assertEquals(2, approved.hits)
        assertEquals(TemplateStatus.APPROVED, approved.status)
        assertEquals(at.plusSeconds(2), approved.updatedAt)
        assertTrue(repo.approved(other, MessageChannel.SMS, "fedbnk").isEmpty())
        assertTrue(repo.proposed(me, 10).isEmpty())
    }

    @Test
    fun `the postgres repository follows the same contract as the in-memory one`() = runBlocking<Unit> {
        contract(InMemoryMessageTemplateRepository(), UUID.randomUUID(), UUID.randomUUID())
        contract(ExposedMessageTemplateRepository(), PostgresTestDatabase.newUser(), PostgresTestDatabase.newUser())
    }

    @Test
    fun `concurrent decisions let exactly one win`() = runBlocking<Unit> {
        val repo = ExposedMessageTemplateRepository()
        val me = PostgresTestDatabase.newUser()
        val t = template(me, status = TemplateStatus.PROPOSED, seen = 3)
        repo.save(t)
        val wins = (1..8).map { i -> async(Dispatchers.IO) { repo.decide(me, t.id, if (i % 2 == 0) TemplateStatus.APPROVED else TemplateStatus.REJECTED, at) } }.awaitAll()
        assertEquals(1, wins.count { it })
    }

    @Test
    fun `a proposal keeps its shape, and no shape with a digit or without the amount can be stored`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        val proposals = ExposedMessageProposalRepository()
        val p = MessageProposal(userId = me, channel = MessageChannel.SMS, sourceRef = "s1", occurredAt = at, direction = IngestDirection.DEBIT,
            amount = BigDecimal("249.00"), accountLast4 = "4321", merchant = "ZEPTO", bankCode = null, model = "qwen3:4b", promptVersion = "read-message-v1",
            createdAt = at, shape = shape)
        proposals.saveNew(p)
        assertEquals(shape, proposals.find(me, p.id)?.shape)
        proposals.saveNew(p.copy(id = UUID.randomUUID(), sourceRef = "s2", shape = null))
        assertEquals(null, proposals.find(me, proposals.pending(me, 10).first { it.sourceRef == "s2" }.id)?.shape)

        fun insert(shapeSql: String) = "INSERT INTO message_templates (id, user_id, channel, direction, shape, status, seen, created_at, updated_at) " +
            "VALUES ('${UUID.randomUUID()}', '$me', 'sms', 'debit', $shapeSql, 'observing', 1, now(), now())"
        dbQuery { TransactionManager.current().exec(insert("'Rs <AMT>'")) }
        for (bad in listOf("'Rs <AMT> on 16-10-26'", "'Card XX4321 <AMT>'", "'Rs <BAL>'")) {
            assertFailsWith<Exception>(bad) { dbQuery { TransactionManager.current().exec(insert(bad)) } }
        }
        assertFailsWith<Exception> { dbQuery { TransactionManager.current().exec("UPDATE message_proposals SET shape = 'Rs 249 <AMT>' WHERE user_id = '$me'") } }
    }
}
