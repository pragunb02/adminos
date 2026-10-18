package dev.adminos.api.domain.balances

import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-720: the latest balance per account is the newest observed, never the newest to arrive. */
class BalanceServiceTest {
    private val ist: ZoneId = ZoneId.of("Asia/Kolkata")
    /** 20 Oct 2026, 10:00 in India. */
    private val now: Instant = Instant.parse("2026-10-20T04:30:00Z")
    private val repo = InMemoryBalanceRepository()
    private val service = BalanceService(repo, Clock.fixed(now, ZoneOffset.UTC))
    private val user = UUID.randomUUID()

    private fun ref(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun obs(amount: String, at: String, hint: String = "1234", bank: String? = "hdfc", source: BalanceSource = BalanceSource.SMS, key: String = "$amount|$at|$hint|$source", who: UUID = user) =
        BalanceObservation(who, bank, hint, BigDecimal(amount), Instant.parse(at), source, ref(key))

    private suspend fun latest() = service.overview(user, ist).accounts

    @Test
    fun `a newer reading replaces an older one and an older one arriving late never does`() = runBlocking<Unit> {
        assertEquals(BalanceRecordResult.Saved, service.record(obs("5000.00", "2026-10-19T05:00:00Z")))
        service.record(obs("4000.00", "2026-10-19T09:00:00Z"))
        service.record(obs("9999.00", "2026-10-15T05:00:00Z")) // a backfilled SMS from days before
        val a = latest().single()
        assertEquals(BigDecimal("4000.00"), a.reading.amount)
        assertEquals(LocalDate.of(2026, 10, 19), a.observedOn)
        assertEquals(1, a.ageDays); assertEquals(Freshness.FRESH, a.freshness)
    }

    @Test
    fun `at the same moment a statement's closing beats an SMS`() = runBlocking<Unit> {
        service.record(obs("100.00", "2026-10-19T18:29:59Z"))
        service.record(obs("120.00", "2026-10-19T18:29:59Z", source = BalanceSource.STATEMENT))
        assertEquals(BigDecimal("120.00"), latest().single().reading.amount)
    }

    @Test
    fun `the same message twice records once`() = runBlocking<Unit> {
        service.record(obs("5000.00", "2026-10-19T05:00:00Z", key = "same"))
        assertEquals(BalanceRecordResult.Duplicate, service.record(obs("5000.00", "2026-10-19T05:00:00Z", key = "same")))
        assertEquals(1, repo.forUser(user).size)
    }

    @Test
    fun `future, implausible and unnamed balances are refused`() = runBlocking<Unit> {
        assertEquals(BalanceRecordResult.Refused(BalanceRefusal.FUTURE), service.record(obs("1.00", "2026-10-20T04:41:00Z")))
        assertEquals(BalanceRecordResult.Saved, service.record(obs("1.00", "2026-10-20T04:39:00Z")), "within the clock slack")
        assertEquals(BalanceRecordResult.Refused(BalanceRefusal.IMPLAUSIBLE), service.record(obs("1000000000.00", "2026-10-19T05:00:00Z")))
        assertEquals(BalanceRecordResult.Refused(BalanceRefusal.IMPLAUSIBLE), service.record(obs("-1000000000.00", "2026-10-19T05:00:00Z")))
        assertEquals(BalanceRecordResult.Refused(BalanceRefusal.IMPLAUSIBLE), service.record(obs("1.005", "2026-10-19T05:00:00Z")))
        for (hint in listOf("", "12", "12345", "12a4", "١٢٣٤")) {
            assertEquals(BalanceRecordResult.Refused(BalanceRefusal.NO_ACCOUNT), service.record(obs("1.00", "2026-10-19T05:00:00Z", hint = hint)), hint)
        }
        assertEquals(BalanceRecordResult.Saved, service.record(obs("999999999.99", "2026-10-19T06:00:00Z", hint = "9999")))
    }

    @Test
    fun `a 3-digit masked hint joins the one known account it ends, and only that`() = runBlocking<Unit> {
        assertEquals(BalanceRecordResult.Refused(BalanceRefusal.ACCOUNT_AMBIGUOUS), service.record(obs("1.00", "2026-10-19T05:00:00Z", hint = "234", bank = "icici")), "no account known yet")
        service.record(obs("100.00", "2026-10-18T05:00:00Z", hint = "1234", bank = "icici"))
        assertEquals(BalanceRecordResult.Saved, service.record(obs("200.00", "2026-10-19T05:00:00Z", hint = "234", bank = "icici")))
        assertEquals(BigDecimal("200.00"), latest().single { it.account.last4 == "1234" }.reading.amount)
        service.record(obs("300.00", "2026-10-18T05:00:00Z", hint = "5234", bank = "icici"))
        assertEquals(BalanceRecordResult.Refused(BalanceRefusal.ACCOUNT_AMBIGUOUS), service.record(obs("1.00", "2026-10-19T06:00:00Z", hint = "234", bank = "icici")))
        assertEquals(BalanceRecordResult.Refused(BalanceRefusal.ACCOUNT_AMBIGUOUS), service.record(obs("1.00", "2026-10-19T06:00:00Z", hint = "234", bank = "sbi")), "another bank's account isn't it")
    }

    @Test
    fun `readings without a bank join the account of the same digits, unless two banks share them`() = runBlocking<Unit> {
        service.record(obs("100.00", "2026-10-18T05:00:00Z", bank = "hdfc"))
        service.record(obs("150.00", "2026-10-19T05:00:00Z", bank = null))
        assertEquals(listOf(BalanceAccount("hdfc", "1234") to BigDecimal("150.00")), latest().map { it.account to it.reading.amount })
        service.record(obs("700.00", "2026-10-18T05:00:00Z", bank = "sbi"))
        assertEquals(listOf(BalanceAccount("hdfc", "1234") to BigDecimal("100.00"), BalanceAccount("sbi", "1234") to BigDecimal("700.00")),
            latest().map { it.account to it.reading.amount }, "the bankless reading can't be placed, so it's left out")
    }

    @Test
    fun `bank codes are normalised and junk ones dropped`() = runBlocking<Unit> {
        service.record(obs("1.00", "2026-10-19T05:00:00Z", bank = " HDFC "))
        service.record(obs("2.00", "2026-10-19T05:00:00Z", hint = "5678", bank = "<b>x</b>"))
        assertEquals(listOf("hdfc", null), latest().map { it.account.bankCode })
    }

    @Test
    fun `ages are labelled fresh, aging and stale, and only the first two count`() = runBlocking<Unit> {
        service.record(obs("10.00", "2026-10-20T03:00:00Z", hint = "1111"))
        service.record(obs("20.00", "2026-10-15T03:00:00Z", hint = "2222"))
        service.record(obs("40.00", "2026-10-13T03:00:00Z", hint = "3333")) // 7 days: the last counted day
        service.record(obs("80.00", "2026-10-12T03:00:00Z", hint = "4444"))
        val o = service.overview(user, ist)
        assertEquals(listOf(Freshness.FRESH, Freshness.AGING, Freshness.AGING, Freshness.STALE), o.accounts.map { it.freshness })
        assertEquals(BigDecimal("70.00"), o.total)
        assertEquals(3, o.counted.size)
    }

    @Test
    fun `another user's readings never show`() = runBlocking<Unit> {
        service.record(obs("5000.00", "2026-10-19T05:00:00Z", who = UUID.randomUUID()))
        assertEquals(emptyList(), latest())
    }

    @Test
    fun `only the newest readings per account are kept`() = runBlocking<Unit> {
        repeat(BalanceRules.KEEP_PER_ACCOUNT + 5) { i -> service.record(obs("$i.00", Instant.parse("2026-10-01T00:00:00Z").plusSeconds(3600L * i).toString())) }
        val kept = repo.forUser(user)
        assertEquals(BalanceRules.KEEP_PER_ACCOUNT, kept.size)
        assertEquals(BigDecimal("24.00"), kept.first().amount)
        assertEquals(BigDecimal("5.00"), kept.last().amount)
    }

    @Test
    fun `nothing prints a balance or a full account`() = runBlocking<Unit> {
        service.record(obs("4321.99", "2026-10-19T05:00:00Z", hint = "8642"))
        val o = service.overview(user, ist)
        val printed = listOf(o.toString(), o.accounts.single().toString(), o.accounts.single().reading.toString(), obs("4321.99", "2026-10-19T05:00:00Z").toString())
        assertTrue(printed.none { "4321" in it }, printed.toString())
        assertFalse("8642" in obs("1.00", "2026-10-19T05:00:00Z", hint = "8642").toString())
    }

    @Test
    fun `readings validate themselves`() {
        val ok = BalanceReading(userId = user, account = BalanceAccount("hdfc", "1234"), amount = BigDecimal("1.00"), observedAt = now,
            source = BalanceSource.SMS, sourceRef = ref("x"), recordedAt = now)
        assertFailsWith<IllegalArgumentException> { ok.copy(amount = BigDecimal("1.0")) }
        assertFailsWith<IllegalArgumentException> { ok.copy(amount = BigDecimal("1000000000.00")) }
        assertFailsWith<IllegalArgumentException> { ok.copy(sourceRef = "not-a-hash") }
        assertFailsWith<IllegalArgumentException> { BalanceAccount("hdfc", "123") }
        assertFailsWith<IllegalArgumentException> { BalanceAccount("HDFC", "1234") }
        assertFailsWith<IllegalArgumentException> { BalanceSource.of("gmail") }
        assertFailsWith<IllegalArgumentException> { AccountBalance(ok.account, ok, LocalDate.of(2026, 10, 20), -1) }
        assertEquals(BalanceSource.STATEMENT, BalanceSource.of("statement"))
        assertEquals("BalanceAccount(?, ••1234)", BalanceAccount(null, "1234").toString())
    }
}
