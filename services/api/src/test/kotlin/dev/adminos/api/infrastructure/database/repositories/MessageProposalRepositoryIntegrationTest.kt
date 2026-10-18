package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.fallback.InMemoryMessageProposalRepository
import dev.adminos.api.domain.ingestion.fallback.MessageChannel
import dev.adminos.api.domain.ingestion.fallback.MessageProposal
import dev.adminos.api.domain.ingestion.fallback.MessageProposalRepository
import dev.adminos.api.domain.ingestion.fallback.ProposalStatus
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-705 on Postgres: message proposals behave like their in-memory twin, are user-scoped, and decide atomically. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageProposalRepositoryIntegrationTest {

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private val at = Instant.parse("2026-10-16T05:00:00Z")

    private fun proposal(user: UUID, ref: String, occurredAt: Instant = at, channel: MessageChannel = MessageChannel.SMS) = MessageProposal(
        userId = user, channel = channel, sourceRef = ref, occurredAt = occurredAt, direction = IngestDirection.CREDIT,
        amount = BigDecimal("1250.50"), accountLast4 = "4321", merchant = "RAHUL K", bankCode = "fedbnk", model = "qwen3:4b",
        promptVersion = "read-message-v1", createdAt = at
    )

    private suspend fun contract(repo: MessageProposalRepository, me: UUID, other: UUID) {
        val old = proposal(me, "old", at.minusSeconds(3600))
        val new = proposal(me, "new")
        assertTrue(repo.saveNew(old))
        assertTrue(repo.saveNew(new))
        assertFalse(repo.saveNew(proposal(me, "new").copy(id = UUID.randomUUID())), "one proposal per (user, channel, message)")
        assertTrue(repo.saveNew(proposal(me, "new", channel = MessageChannel.WHATSAPP)), "another channel is another message")
        val theirs = proposal(other, "new")
        assertTrue(repo.saveNew(theirs), "another user may have the same message")

        assertTrue(repo.exists(me, MessageChannel.SMS, "old"))
        assertFalse(repo.exists(me, MessageChannel.GMAIL, "old"))
        assertEquals(new, repo.find(me, new.id))
        assertNull(repo.find(me, theirs.id), "another user's proposal is not found")
        assertEquals(listOf(new.id, old.id), repo.pending(me, 10).filter { it.channel == MessageChannel.SMS }.map { it.id })
        assertEquals(1, repo.pending(me, 1).size)

        assertFalse(repo.decide(me, theirs.id, ProposalStatus.CONFIRMED, at, null))
        val event = UUID.randomUUID()
        assertTrue(repo.decide(me, new.id, ProposalStatus.CONFIRMED, at.plusSeconds(60), event))
        assertFalse(repo.decide(me, new.id, ProposalStatus.DISMISSED, at.plusSeconds(61), null), "decided once")
        assertEquals(ProposalStatus.CONFIRMED, repo.find(me, new.id)?.status)
        assertEquals(event, repo.find(me, new.id)?.ingestEventId)
        assertEquals(at.plusSeconds(60), repo.find(me, new.id)?.decidedAt)
        assertFalse(repo.reopen(other, new.id))
        assertTrue(repo.reopen(me, new.id))
        assertEquals(ProposalStatus.PENDING, repo.find(me, new.id)?.status)
        assertNull(repo.find(me, new.id)?.ingestEventId)
        assertFalse(repo.reopen(me, new.id), "only a confirmed proposal reopens")
        assertTrue(repo.decide(me, old.id, ProposalStatus.DISMISSED, at, null))
        assertTrue(repo.exists(me, MessageChannel.SMS, "old"), "dismissed proposals are kept, so the message isn't read again")
        assertFailsWith<IllegalArgumentException> { repo.decide(me, old.id, ProposalStatus.PENDING, at, null) }
    }

    @Test
    fun `the postgres repository follows the same contract as the in-memory one`() = runBlocking<Unit> {
        contract(InMemoryMessageProposalRepository(), UUID.randomUUID(), UUID.randomUUID())
        contract(ExposedMessageProposalRepository(), PostgresTestDatabase.newUser(), PostgresTestDatabase.newUser())
    }

    @Test
    fun `concurrent decisions on postgres let exactly one win`() = runBlocking<Unit> {
        val repo = ExposedMessageProposalRepository()
        val me = PostgresTestDatabase.newUser()
        val p = proposal(me, "race")
        repo.saveNew(p)
        val wins = (1..8).map { i ->
            async(Dispatchers.IO) { repo.decide(me, p.id, if (i % 2 == 0) ProposalStatus.CONFIRMED else ProposalStatus.DISMISSED, at, null) }
        }.awaitAll()
        assertEquals(1, wins.count { it })
    }

    @Test
    fun `the table refuses rows that break its rules`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        fun sql(values: String) = "INSERT INTO message_proposals (id, user_id, channel, source_ref, occurred_at, direction, amount, model, prompt_version, status, created_at, decided_at) " +
            "VALUES ('${UUID.randomUUID()}', '$me', $values)"
        val ok = "'sms', 'r', now(), 'debit', 1.00, 'qwen3:4b', 'v1', 'pending', now(), NULL"
        dbQuery { TransactionManager.current().exec(sql(ok)) }
        listOf(
            "'email', 'r2', now(), 'debit', 1.00, 'qwen3:4b', 'v1', 'pending', now(), NULL",
            "'sms', 'r3', now(), 'debit', 0, 'qwen3:4b', 'v1', 'pending', now(), NULL",
            "'sms', 'r4', now(), 'sideways', 1.00, 'qwen3:4b', 'v1', 'pending', now(), NULL",
            "'sms', 'r5', now(), 'debit', 1.00, 'Bad Model!', 'v1', 'pending', now(), NULL",
            "'sms', 'r6', now(), 'debit', 1.00, 'qwen3:4b', 'v1', 'confirmed', now(), NULL",
            "'sms', 'r7', now(), 'debit', 1.00, 'qwen3:4b', 'v1', 'pending', now(), now()",
            "'sms', '', now(), 'debit', 1.00, 'qwen3:4b', 'v1', 'pending', now(), NULL"
        ).forEach { values -> assertFailsWith<Exception>(values) { dbQuery { TransactionManager.current().exec(sql(values)) } } }
    }
}
