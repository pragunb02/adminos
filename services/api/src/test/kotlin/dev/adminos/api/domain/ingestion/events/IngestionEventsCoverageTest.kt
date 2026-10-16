package dev.adminos.api.domain.ingestion.events

import dev.adminos.api.InfraIngestTestKit.assertRejectsMissingFields
import dev.adminos.api.InfraIngestTestKit.assertRoundTrips
import dev.adminos.api.domain.financial.PaymentMethod
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** IngestEvent invariants, the DTO mapping and the in-memory outbox (AOS-312). */
class IngestionEventsCoverageTest {

    private val user = UUID.randomUUID()
    private fun event(
        sourceRef: String = "sms-ref-1", amount: String = "249.00", currency: String = "INR", accountHint: String? = "1234",
        merchantRaw: String? = "SWIGGY", paymentRef: String? = null, receivedAt: Instant = Instant.parse("2026-10-01T03:42:00Z")
    ) = IngestEvent(
        userId = user, source = IngestSource.SMS, sourceRef = sourceRef, occurredAt = Instant.parse("2026-10-01T03:42:00Z"),
        direction = IngestDirection.DEBIT, amount = BigDecimal(amount), currency = currency, accountHint = accountHint,
        merchantRaw = merchantRaw, paymentRef = paymentRef, receivedAt = receivedAt
    )

    // ── Invariants ──

    @Test
    fun `a blank sourceRef is rejected`() {
        assertFailsWith<IllegalArgumentException> { event(sourceRef = " ") }
    }

    @Test
    fun `a sourceRef over 255 characters is rejected`() {
        assertFailsWith<IllegalArgumentException> { event(sourceRef = "r".repeat(256)) }
    }

    @Test
    fun `a zero amount is rejected`() {
        assertFailsWith<IllegalArgumentException> { event(amount = "0.00") }
    }

    @Test
    fun `an amount with three decimals is rejected`() {
        assertFailsWith<IllegalArgumentException> { event(amount = "249.005") }
    }

    @Test
    fun `a lowercase currency is rejected`() {
        assertFailsWith<IllegalArgumentException> { event(currency = "inr") }
    }

    @Test
    fun `an account hint must be exactly four digits`() {
        assertFailsWith<IllegalArgumentException> { event(accountHint = "12345") }
    }

    @Test
    fun `a merchant over 255 characters is rejected`() {
        assertFailsWith<IllegalArgumentException> { event(merchantRaw = "M".repeat(256)) }
    }

    @Test
    fun `a payment reference must be 6 to 32 letters or digits`() {
        assertFailsWith<IllegalArgumentException> { event(paymentRef = "12-34") }
        assertEquals("123456789012", event(paymentRef = "123456789012").paymentRef)
    }

    @Test
    fun `optional fields may all be absent`() {
        val e = event(accountHint = null, merchantRaw = null, paymentRef = null)
        assertNull(e.accountHint); assertNull(e.merchantRaw); assertNull(e.paymentRef)
    }

    // ── DTO mapping ──

    private fun dto(source: String = "sms", amount: String = "249.00", paymentMethod: String? = null) = IngestEventDto(
        source = source, sourceRef = "ref-1", occurredAt = "2026-10-01T09:12:00+05:30", direction = "debit", amount = amount, paymentMethod = paymentMethod
    )

    @Test
    fun `a known payment method is mapped`() {
        assertEquals(PaymentMethod.CARD_CREDIT, dto(paymentMethod = "card_credit").toDomain(user, "events[0]").paymentMethod)
    }

    @Test
    fun `an unknown payment method names the field`() {
        val e = assertFailsWith<IllegalArgumentException> { dto(paymentMethod = "cheque").toDomain(user, "events[2]") }
        assertTrue(e.message!!.startsWith("events[2].paymentMethod"), e.message)
    }

    @Test
    fun `an invariant violation is prefixed with the event position`() {
        val e = assertFailsWith<IllegalArgumentException> { dto(amount = "-5").toDomain(user, "events[4]") }
        assertEquals("events[4]: amount must be positive", e.message)
    }

    @Test
    fun `intake DTOs round-trip with and without defaults`() {
        assertRoundTrips(IngestEventsRequest.serializer(),
            """{"events":[{"source":"sms","sourceRef":"r","occurredAt":"2026-10-01T09:12:00+05:30","direction":"debit","amount":"1.00",
               "currency":"USD","merchantRaw":"m","accountHint":"1234","paymentMethod":"upi","upiVpa":"a@ybl","paymentRef":"123456"}]}""",
            """{"events":[{"source":"sms","sourceRef":"r","occurredAt":"2026-10-01T09:12:00+05:30","direction":"debit","amount":"1.00"}]}""")
        assertRoundTrips(IngestEventsResponse.serializer(), """{"accepted":1,"duplicates":0}""")
        assertRejectsMissingFields(IngestEventDto.serializer())
        assertRejectsMissingFields(IngestEventsRequest.serializer())
        assertRejectsMissingFields(IngestEventsResponse.serializer())
    }

    // ── In-memory outbox ──

    private val repo = InMemoryIngestEventRepository()

    @Test
    fun `pending events are listed oldest first`() = runBlocking<Unit> {
        repo.saveNew(listOf(event(sourceRef = "b", receivedAt = Instant.parse("2026-10-01T05:00:00Z")), event(sourceRef = "a", receivedAt = Instant.parse("2026-10-01T04:00:00Z"))))
        assertEquals(listOf("a", "b"), repo.findPending().map { it.sourceRef })
    }

    @Test
    fun `claim takes pending events and stale claims but not fresh claims or finished events`() = runBlocking<Unit> {
        repo.saveNew(listOf(event(sourceRef = "fresh"), event(sourceRef = "done")))
        repo.claim(10, staleBefore = Instant.EPOCH) // both now PROCESSING, claimed just now
        repo.finish(repo.findByUser(user).first { it.sourceRef == "done" }.id, IngestEventStatus.PROCESSED)
        repo.saveNew(listOf(event(sourceRef = "new")))

        assertEquals(listOf("new"), repo.claim(10, staleBefore = Instant.EPOCH).map { it.sourceRef }, "fresh claims are left alone")
        val reclaimed = repo.claim(10, staleBefore = Instant.now().plusSeconds(60)).map { it.sourceRef }.toSet()
        assertEquals(setOf("fresh", "new"), reclaimed, "stale claims are retried; processed events never")
    }

    @Test
    fun `finishing an unknown event does nothing`() = runBlocking<Unit> {
        repo.finish(UUID.randomUUID(), IngestEventStatus.FAILED, "gone")
        assertTrue(repo.findByUser(user).isEmpty())
    }

    @Test
    fun `a PROCESSING event without a claim time is not treated as stale`() = runBlocking<Unit> {
        repo.saveNew(listOf(event(sourceRef = "odd").copy(status = IngestEventStatus.PROCESSING, claimedAt = null)))
        assertTrue(repo.claim(10, staleBefore = Instant.EPOCH).isEmpty())
    }
}
