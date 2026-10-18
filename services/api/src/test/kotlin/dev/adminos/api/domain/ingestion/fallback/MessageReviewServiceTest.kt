package dev.adminos.api.domain.ingestion.fallback

import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.IngestSource
import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageReviewServiceTest {
    private val clock = TestClock(Instant.parse("2026-10-16T08:00:00Z"))
    private val me = UUID(0, 1)
    private val other = UUID(0, 2)
    private val proposals = InMemoryMessageProposalRepository()
    private val events = InMemoryIngestEventRepository()
    private val service = MessageReviewService(proposals, IngestEventService(events, clock), clock)

    private fun proposal(user: UUID = me, ref: String = "ref-1", at: Instant = Instant.parse("2026-10-16T05:00:00Z"), channel: MessageChannel = MessageChannel.SMS) = MessageProposal(
        userId = user, channel = channel, sourceRef = ref, occurredAt = at, direction = IngestDirection.DEBIT, amount = BigDecimal("249.00"),
        accountLast4 = "4321", merchant = "ZEPTO", bankCode = "fedbnk", model = "qwen3:4b", promptVersion = "read-message-v1", createdAt = clock.instant()
    )

    private suspend fun stored(p: MessageProposal = proposal()) = p.also { proposals.saveNew(it) }

    @Test
    fun `pending lists only the user's undecided proposals, newest message first`() = runBlocking<Unit> {
        val old = stored(proposal(ref = "old", at = Instant.parse("2026-10-10T00:00:00Z")))
        val new = stored(proposal(ref = "new"))
        stored(proposal(user = other, ref = "theirs"))
        val decided = stored(proposal(ref = "done"))
        service.dismiss(me, decided.id)
        assertEquals(listOf(new.id, old.id), service.pending(me).map { it.id })
    }

    @Test
    fun `confirming makes one ordinary intake event marked as AI-read`() = runBlocking<Unit> {
        val p = stored()
        val outcome = assertIs<ConfirmOutcome.Confirmed>(service.confirm(me, p.id))
        assertEquals(false, outcome.duplicate)
        val e = events.findByUser(me).single()
        assertEquals(outcome.eventId, e.id)
        assertEquals(IngestSource.SMS, e.source)
        assertEquals("ref-1", e.sourceRef)
        assertEquals(p.occurredAt, e.occurredAt)
        assertEquals(BigDecimal("249.00"), e.amount)
        assertEquals("ZEPTO", e.merchantRaw)
        assertEquals("4321", e.accountHint)
        assertEquals(mapOf("bank" to "fedbnk", "extractedBy" to "ai:qwen3:4b:read-message-v1", "aiRead" to "confirmed"), e.metadata)
        val after = proposals.find(me, p.id)
        assertEquals(ProposalStatus.CONFIRMED, after?.status)
        assertEquals(e.id, after?.ingestEventId)
        assertTrue(service.pending(me).isEmpty())
    }

    @Test
    fun `a whatsapp proposal becomes a whatsapp event`() = runBlocking<Unit> {
        val p = stored(proposal(channel = MessageChannel.WHATSAPP).copy(bankCode = null))
        service.confirm(me, p.id)
        val e = events.findByUser(me).single()
        assertEquals(IngestSource.WHATSAPP, e.source)
        assertEquals(mapOf("extractedBy" to "ai:qwen3:4b:read-message-v1", "aiRead" to "confirmed"), e.metadata)
    }

    @Test
    fun `another user's proposal can't be confirmed or dismissed`() = runBlocking<Unit> {
        val theirs = stored(proposal(user = other))
        assertEquals(ConfirmOutcome.NotFound, service.confirm(me, theirs.id))
        assertEquals(false, service.dismiss(me, theirs.id))
        assertEquals(ProposalStatus.PENDING, proposals.find(other, theirs.id)?.status)
        assertNull(proposals.find(me, theirs.id))
        assertTrue(events.findByUser(me).isEmpty() && events.findByUser(other).isEmpty())
    }

    @Test
    fun `a decided proposal can't be decided again`() = runBlocking<Unit> {
        val p = stored()
        service.confirm(me, p.id)
        assertEquals(ConfirmOutcome.NotFound, service.confirm(me, p.id))
        assertEquals(false, service.dismiss(me, p.id))
        val q = stored(proposal(ref = "ref-2"))
        assertEquals(true, service.dismiss(me, q.id))
        assertEquals(ConfirmOutcome.NotFound, service.confirm(me, q.id))
        assertEquals(1, events.findByUser(me).size, "a dismissed proposal never reaches the intake")
        assertEquals(ConfirmOutcome.NotFound, service.confirm(me, UUID.randomUUID()))
    }

    @Test
    fun `a message already in the intake confirms as a duplicate`() = runBlocking<Unit> {
        val p = stored()
        events.saveNew(listOf(IngestEvent(userId = me, source = IngestSource.SMS, sourceRef = "ref-1", occurredAt = p.occurredAt,
            direction = IngestDirection.DEBIT, amount = BigDecimal("249.00"))))
        assertEquals(true, assertIs<ConfirmOutcome.Confirmed>(service.confirm(me, p.id)).duplicate)
        assertEquals(1, events.findByUser(me).size)
    }

    @Test
    fun `if the intake fails the proposal goes back to pending`() = runBlocking<Unit> {
        val broken = object : IngestEventRepository by events {
            override suspend fun saveNew(events: List<IngestEvent>): Int = error("database down")
        }
        val failing = MessageReviewService(proposals, IngestEventService(broken, clock), clock)
        val p = stored()
        assertFailsWith<IllegalStateException> { failing.confirm(me, p.id) }
        val back = proposals.find(me, p.id)
        assertEquals(ProposalStatus.PENDING, back?.status)
        assertNull(back?.decidedAt)
        assertNull(back?.ingestEventId)
        assertIs<ConfirmOutcome.Confirmed>(service.confirm(me, p.id), "and can be confirmed once it works")
    }

    @Test
    fun `the in-memory repository keeps its own rules`() = runBlocking<Unit> {
        val p = stored()
        assertEquals(false, proposals.saveNew(proposal().copy(id = UUID.randomUUID())), "one proposal per message")
        assertTrue(proposals.exists(me, MessageChannel.SMS, "ref-1"))
        assertEquals(false, proposals.exists(me, MessageChannel.WHATSAPP, "ref-1"))
        assertEquals(false, proposals.reopen(me, p.id), "only a confirmed proposal reopens")
        assertFailsWith<IllegalArgumentException> { proposals.decide(me, p.id, ProposalStatus.PENDING, clock.instant(), null) }
    }

    @Test
    fun `a proposal checks its own fields and never prints them`() {
        assertFailsWith<IllegalArgumentException> { proposal().copy(amount = BigDecimal("0")) }
        assertFailsWith<IllegalArgumentException> { proposal().copy(amount = BigDecimal("1.001")) }
        assertFailsWith<IllegalArgumentException> { proposal().copy(accountLast4 = "12") }
        assertFailsWith<IllegalArgumentException> { proposal().copy(merchant = "x") }
        assertFailsWith<IllegalArgumentException> { proposal().copy(sourceRef = " ") }
        assertFailsWith<IllegalArgumentException> { proposal().copy(status = ProposalStatus.DISMISSED) }
        assertFailsWith<IllegalArgumentException> { proposal().copy(decidedAt = clock.instant()) }
        assertFailsWith<IllegalArgumentException> { proposal().copy(ingestEventId = UUID.randomUUID()) }
        val s = proposal().toString()
        assertTrue("ZEPTO" !in s && "249" !in s && "4321" !in s, s)
        assertEquals(MessageChannel.GMAIL, MessageChannel.fromKey("gmail"))
        assertNull(MessageChannel.fromKey("email"))
    }
}
