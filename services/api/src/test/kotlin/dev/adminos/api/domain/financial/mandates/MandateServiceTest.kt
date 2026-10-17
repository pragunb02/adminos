package dev.adminos.api.domain.financial.mandates

import dev.adminos.api.domain.financial.*
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.YearMonth
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mandates from SMS and their debits (AOS-502). */
class MandateServiceTest {
    private val userId = UUID.randomUUID()
    private val repo = InMemoryMandateRepository()
    private val transactions = InMemoryTransactionRepository()
    private val service = MandateService(repo, transactions, Clock.fixed(Instant.parse("2026-10-16T06:30:00Z"), ZoneOffset.UTC))
    private val t0 = Instant.parse("2026-10-01T05:00:00Z")

    private fun notice(kind: MandateNoticeKind = MandateNoticeKind.CREATED, payee: String = "NETFLIX", amount: String? = "649.00",
                       umn: String? = "7a8b9c0d1e@hdfcbank", frequency: MandateFrequency = MandateFrequency.MONTHLY, debitOn: LocalDate? = null,
                       rail: MandateRail = MandateRail.UPI_AUTOPAY, validUntil: LocalDate? = null) =
        MandateNotice(kind, rail, payee, amount?.let(::BigDecimal), umn, frequency, debitOn, validUntil, bank = "hdfc")

    private suspend fun record(n: MandateNotice, at: Instant = t0, ref: String = UUID.randomUUID().toString()) = service.record(userId, n, at, ref)

    // ── From SMS ──

    @Test
    fun `a set-up message creates an active mandate`() = runBlocking<Unit> {
        val m = record(notice())
        assertEquals(MandateStatus.ACTIVE, m.status)
        assertEquals(LocalDate.of(2026, 10, 1), m.createdOn)
        assertEquals(BigDecimal("649.00"), m.amount)
        assertEquals("hdfc", m.bank)
        assertEquals(listOf(m), repo.findByUserId(userId))
    }

    @Test
    fun `the same message twice changes nothing`() = runBlocking<Unit> {
        val m = record(notice(), ref = "sms-1")
        assertEquals(m, record(notice(kind = MandateNoticeKind.REVOKED), at = t0.plusSeconds(60), ref = "sms-1"))
    }

    @Test
    fun `the UMN identifies a mandate, and without one, the same payee on the same rail does`() = runBlocking<Unit> {
        val m = record(notice())
        record(notice(kind = MandateNoticeKind.PAUSED, payee = "Netflix Entertainment", umn = "7A8B9C0D1E@HDFCBANK"), at = t0.plusSeconds(60))
        assertEquals(MandateStatus.PAUSED, repo.findByUserId(userId).single().status)
        record(notice(kind = MandateNoticeKind.RESUMED, payee = "netflix", umn = null), at = t0.plusSeconds(120))
        assertEquals(MandateStatus.ACTIVE, repo.findByUserId(userId).single().status)
        record(notice(payee = "NETFLIX", umn = null, rail = MandateRail.CARD_SI), at = t0.plusSeconds(180))
        assertEquals(2, repo.findByUserId(userId).size, "another rail is another mandate")
        assertEquals(m.id, repo.findByUmn(userId, "7a8b9c0d1e@hdfcbank")!!.id)
    }

    @Test
    fun `an older message arriving late never undoes a newer status`() = runBlocking<Unit> {
        record(notice(kind = MandateNoticeKind.REVOKED, amount = null), at = t0.plusSeconds(3600))
        val m = record(notice(kind = MandateNoticeKind.CREATED, amount = "799.00"), at = t0)
        assertEquals(MandateStatus.REVOKED, m.status)
        assertEquals(LocalDate.of(2026, 10, 1), m.createdOn)
        assertEquals(BigDecimal("799.00"), m.amount, "the revoke didn't say an amount, so the set-up's is kept")
    }

    @Test
    fun `a pre-debit notice sets the next debit, and dates only move forward`() = runBlocking<Unit> {
        record(notice())
        record(notice(kind = MandateNoticeKind.PRE_DEBIT, amount = "699.00", debitOn = LocalDate.of(2026, 11, 10)), at = t0.plusSeconds(60))
        record(notice(kind = MandateNoticeKind.PRE_DEBIT, amount = "649.00", debitOn = LocalDate.of(2026, 10, 10)), at = t0.plusSeconds(120))
        val m = repo.findByUserId(userId).single()
        assertEquals(LocalDate.of(2026, 11, 10), m.nextDebitOn)
        assertEquals(BigDecimal("699.00"), m.nextDebitAmount)
        assertEquals(BigDecimal("649.00"), m.amount, "a pre-debit amount isn't the mandate's amount")
        assertEquals(MandateStatus.ACTIVE, m.status)
    }

    @Test
    fun `we can learn of a mandate from a pre-debit or a revoke alone, and the frequency fills in`() = runBlocking<Unit> {
        val m = record(notice(kind = MandateNoticeKind.PRE_DEBIT, umn = null, frequency = MandateFrequency.UNKNOWN, debitOn = LocalDate.of(2026, 10, 20)))
        assertEquals(MandateStatus.ACTIVE, m.status)
        assertNull(m.createdOn)
        assertEquals(MandateFrequency.MONTHLY, record(notice(umn = null), at = t0.plusSeconds(60)).frequency)
        assertEquals(MandateStatus.REVOKED, record(notice(kind = MandateNoticeKind.REVOKED, payee = "Hotstar", umn = null)).status)
    }

    @Test
    fun `an older message's frequency doesn't replace a newer known one`() = runBlocking<Unit> {
        record(notice(frequency = MandateFrequency.YEARLY), at = t0.plusSeconds(60))
        assertEquals(MandateFrequency.YEARLY, record(notice(frequency = MandateFrequency.MONTHLY), at = t0).frequency)
    }

    // ── Debits ──

    private suspend fun debit(amount: String = "649.00", merchant: String? = "NETFLIX", at: Instant = Instant.parse("2026-11-10T04:00:00Z"),
                              metadata: Map<String, Any?>? = mapOf("mandate" to "true"), nature: TransactionNature = TransactionNature.SPEND) =
        transactions.save(Transaction(userId = userId, type = TransactionType.DEBIT, nature = nature, amount = BigDecimal(amount),
            merchantName = merchant, transactedAt = at, metadata = metadata))

    @Test
    fun `a mandate debit is recorded, moves the next debit on, and marks the transaction recurring`() = runBlocking<Unit> {
        val m = record(notice())
        val t = debit()
        val paid = assertNotNull(service.recordDebit(t))
        assertEquals(t.id, paid.lastDebitTxnId)
        assertEquals(BigDecimal("649.00"), paid.lastDebitAmount)
        assertEquals(LocalDate.of(2026, 12, 10), paid.nextDebitOn)
        val stored = transactions.findById(t.id)!!
        assertTrue(stored.isRecurring)
        assertEquals(m.id, stored.recurringGroupId)
        assertNull(service.recordDebit(stored), "once per transaction")
    }

    @Test
    fun `the UMN in the debit SMS is enough on its own`() = runBlocking<Unit> {
        record(notice())
        assertNotNull(service.recordDebit(debit(merchant = null, metadata = mapOf("mandate" to "true", "umn" to "7A8B9C0D1E@hdfcbank"))))
    }

    @Test
    fun `without the mandate marker, the expected date (within 3 days) can stand in`() = runBlocking<Unit> {
        record(notice(kind = MandateNoticeKind.PRE_DEBIT, debitOn = LocalDate.of(2026, 11, 10)))
        assertNull(service.recordDebit(debit(metadata = null, at = Instant.parse("2026-11-20T04:00:00Z"))), "10 days off")
        assertNotNull(service.recordDebit(debit(metadata = null, at = Instant.parse("2026-11-12T04:00:00Z"))))
    }

    @Test
    fun `too much money, another payee, a refund, or two equal mandates match nothing`() = runBlocking<Unit> {
        record(notice())
        assertNull(service.recordDebit(debit(amount = "700.00")), "over the mandate amount (5% slack)")
        assertNull(service.recordDebit(debit(merchant = "SWIGGY")))
        assertNull(service.recordDebit(debit(nature = TransactionNature.REFUND)))
        record(notice(umn = "other-umn-1234", rail = MandateRail.CARD_SI))
        assertNull(service.recordDebit(debit()), "two Netflix mandates: can't tell")
    }

    @Test
    fun `a one-time mandate is completed by its debit`() = runBlocking<Unit> {
        record(notice(frequency = MandateFrequency.ONE_TIME))
        assertEquals(MandateStatus.COMPLETED, service.recordDebit(debit())!!.status)
    }

    // ── As seen today ──

    @Test
    fun `past its validity a mandate shows as expired, and the next debit rolls forward, and unused ones are flagged`() = runBlocking<Unit> {
        val today = LocalDate.of(2026, 10, 16)
        val base = Mandate(userId = userId, rail = MandateRail.UPI_AUTOPAY, payee = "Gym", frequency = MandateFrequency.MONTHLY,
            statusChangedAt = t0, createdOn = LocalDate.of(2026, 6, 1), nextDebitOn = LocalDate.of(2026, 8, 5))
        assertEquals(MandateStatus.EXPIRED, service.view(base.copy(validUntil = LocalDate.of(2026, 10, 1)), today).status)
        assertNull(service.view(base.copy(validUntil = LocalDate.of(2026, 10, 1)), today).nextDebitOn)
        val v = service.view(base, today)
        assertEquals(LocalDate.of(2026, 11, 5), v.nextDebitOn)
        assertTrue(v.dormant, "set up in June, nothing taken since")
        assertFalse(service.view(base.copy(lastDebitAt = Instant.parse("2026-10-05T04:00:00Z")), today).dormant)
        assertFalse(service.view(base.copy(frequency = MandateFrequency.AS_PRESENTED), today).dormant)
        assertNull(service.view(base.copy(frequency = MandateFrequency.AS_PRESENTED), today).nextDebitOn, "a past date with no schedule")
        assertFalse(service.view(base.copy(createdOn = null), today).dormant)
    }

    @Test
    fun `the list puts active mandates first, soonest debit first`() = runBlocking<Unit> {
        record(notice(kind = MandateNoticeKind.REVOKED, payee = "Old", umn = null))
        record(notice(kind = MandateNoticeKind.PRE_DEBIT, payee = "Later", umn = null, debitOn = LocalDate.of(2026, 11, 20)))
        record(notice(kind = MandateNoticeKind.PRE_DEBIT, payee = "Sooner", umn = null, debitOn = LocalDate.of(2026, 10, 20)))
        assertEquals(listOf("Sooner", "Later", "Old"), service.list(userId, LocalDate.of(2026, 10, 16)).map { it.mandate.payee })
    }

    @Test
    fun `payee names compare without case, punctuation or company suffixes`() {
        assertEquals("bajaj finance", MandateService.normalize("BAJAJ FINANCE LTD."))
        assertEquals("netflix", MandateService.normalize("Netflix India Pvt. Ltd"))
    }

    // ── Dues calendar ──

    @Test
    fun `mandate debits show on the dues calendar, replacing the same subscription's renewal`() = runBlocking<Unit> {
        val subs = InMemorySubscriptionRepository()
        subs.save(Subscription(userId = userId, name = "Netflix", amount = BigDecimal("649.00"), billingCycle = BillingCycle.MONTHLY, nextBillingDate = LocalDate.of(2026, 10, 22)))
        subs.save(Subscription(userId = userId, name = "Spotify", amount = BigDecimal("119.00"), billingCycle = BillingCycle.MONTHLY, nextBillingDate = LocalDate.of(2026, 10, 25)))
        record(notice(kind = MandateNoticeKind.PRE_DEBIT, amount = "699.00", debitOn = LocalDate.of(2026, 10, 22)))
        record(notice(kind = MandateNoticeKind.PRE_DEBIT, payee = "Unknown amount", umn = null, amount = null, debitOn = LocalDate.of(2026, 10, 23)))
        val month = DuesService(InMemoryBillRepository(), subs, service).month(userId, YearMonth.of(2026, 10), LocalDate.of(2026, 10, 16))
        assertEquals(listOf("NETFLIX" to DueKind.MANDATE, "Spotify" to DueKind.RENEWAL), month.items.map { it.name to it.kind })
        assertEquals(BigDecimal("699.00"), month.items.first().amount)
        assertEquals("upi_autopay", month.items.first().detail)
        assertEquals(BigDecimal("818.00"), month.totals.renewals)
    }
}
