package dev.adminos.api.domain.wealth

import dev.adminos.api.domain.wealth.WealthFixtures.account
import dev.adminos.api.domain.wealth.WealthFixtures.batch
import dev.adminos.api.domain.wealth.WealthFixtures.buy
import dev.adminos.api.domain.wealth.WealthFixtures.instrument
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wealth store's behaviour (AOS-802), run against the in-memory store ([InMemoryWealthStoreTest]) and Postgres
 * (`ExposedWealthStoreIntegrationTest`), so the fakes behave like the real thing (Liskov, ENGINEERING §5).
 */
abstract class WealthStoreContract {
    abstract val accounts: InvestmentAccountRepository
    abstract val instruments: InstrumentRepository
    abstract val ledger: InvestmentLedger
    abstract suspend fun newUser(): UUID

    private val day = LocalDate.of(2025, 4, 10)

    @Test
    fun `the same folio is one account, found by its blind index, and only for its owner`() = runBlocking<Unit> {
        val user = newUser()
        val first = accounts.findOrCreate(account(user, "90000001"))
        val again = accounts.findOrCreate(account(user, "9000 0001"))
        assertEquals(first.id, again.id)
        val other = accounts.findOrCreate(account(user, "90000002"))
        assertEquals(listOf(first.id, other.id).toSet(), accounts.findByUser(user).map { it.id }.toSet())
        assertEquals("90000001", WealthFixtures.identifiers.reveal(accounts.findById(user, first.id)!!.identifier, "investment_accounts:${first.id}:identifier"))

        val stranger = newUser()
        assertNull(accounts.findById(stranger, first.id))
        assertTrue(accounts.findByUser(stranger).isEmpty())
        // The same folio digits for someone else are their own account.
        assertTrue(accounts.findOrCreate(account(stranger, "90000001")).id != first.id)
    }

    @Test
    fun `instruments match on ISIN first, then AMFI code`() = runBlocking<Unit> {
        val n = WealthFixtures.uniqueN()
        val growth = instruments.findOrCreate(instrument(n, amfi = "7$n"))
        assertEquals(growth.id, instruments.findOrCreate(instrument(n, amfi = "7$n").copy(name = "Renamed")).id)
        // The IDCW reinvestment twin shares the AMFI code but has its own ISIN: a different instrument.
        val twin = instruments.findOrCreate(instrument(n + 1, amfi = "7$n"))
        assertTrue(twin.id != growth.id)
        // An ISIN-less row (e.g. a manual entry) falls back to the AMFI code.
        val byCode = instruments.findOrCreate(Instrument(name = "No ISIN", assetClass = AssetClass.EQUITY_IN, amfiCode = "7$n"))
        assertEquals(growth.id, byCode.id)
        val ticker = instruments.findOrCreate(Instrument(name = "Example Corp", assetClass = AssetClass.EQUITY_FOREIGN, ticker = "EX$n", exchange = "NYSE", currency = Currency.getInstance("USD")))
        assertEquals(ticker.id, instruments.findOrCreate(ticker.copy(id = UUID.randomUUID())).id)
        assertEquals(setOf(growth.id, twin.id), instruments.findByIds(listOf(growth.id, twin.id, growth.id)).map { it.id }.toSet())
        assertTrue(instruments.findByIds(emptyList()).isEmpty())
        assertTrue(instruments.mutualFunds().map { it.id }.containsAll(listOf(growth.id, twin.id)))
        assertFalse(instruments.mutualFunds().any { it.id == ticker.id })
        assertEquals(growth.id, instruments.findById(growth.id)?.id)
        assertNull(instruments.findById(UUID.randomUUID()))
    }

    @Test
    fun `recording a batch twice adds no duplicates and keeps identical rows that really repeat`() = runBlocking<Unit> {
        val user = newUser()
        val acct = accounts.findOrCreate(account(user))
        val fund = instruments.findOrCreate(instrument(WealthFixtures.uniqueN()))
        val b1 = batch(user, events = 3)
        val rows = listOf(
            buy(user, acct, fund, b1, day, "100.000", "10.0000", "1000.00"),
            // Two SIPs of the same amount on the same day are two rows (occurrence 0 and 1).
            buy(user, acct, fund, b1, day.plusMonths(1), "95.238", "10.5000", "1000.00", InvestmentTxnType.SIP_BUY, 0),
            buy(user, acct, fund, b1, day.plusMonths(1), "95.238", "10.5000", "1000.00", InvestmentTxnType.SIP_BUY, 1),
        )
        val first = ledger.record(b1, rows)
        assertEquals(3, first.added); assertEquals(0, first.duplicates); assertEquals(3, first.batch.added)

        val b2 = batch(user, events = 3)
        val second = ledger.record(b2, rows.map { it.copy(id = UUID.randomUUID(), batchId = b2.id) })
        assertEquals(0, second.added); assertEquals(3, second.duplicates)
        assertEquals(3, ledger.active(user).size)
        val active = ledger.active(user)
        assertEquals(listOf(day, day.plusMonths(1), day.plusMonths(1)), active.map { it.tradeDate })
        assertEquals(0, BigDecimal("1000").compareTo(active.first().amount))
        assertEquals(Quantity.parse("100"), active.first().units)
        assertEquals(UnitPrice.parse("10"), active.first().unitPrice)
        assertEquals(InvestmentTxnType.SIP_BUY, active.last().type)
    }

    @Test
    fun `an undone batch hides its rows, and the same file can then be imported again`() = runBlocking<Unit> {
        val user = newUser()
        val acct = accounts.findOrCreate(account(user))
        val fund = instruments.findOrCreate(instrument(WealthFixtures.uniqueN()))
        val b1 = batch(user, events = 1)
        ledger.record(b1, listOf(buy(user, acct, fund, b1, day, "1", "10", "10.00")))
        assertTrue(ledger.undo(user, b1.id, Instant.parse("2026-10-03T00:00:00Z")))
        assertFalse(ledger.undo(user, b1.id, Instant.parse("2026-10-03T00:00:00Z")), "already undone")
        assertTrue(ledger.active(user).isEmpty())
        assertEquals(ImportStatus.UNDONE, ledger.findBatch(user, b1.id)?.status)
        assertEquals(Instant.parse("2026-10-03T00:00:00Z"), ledger.findBatch(user, b1.id)?.undoneAt)

        val b2 = batch(user, events = 1)
        val again = ledger.record(b2, listOf(buy(user, acct, fund, b2, day, "1", "10", "10.00")))
        assertEquals(1, again.added)
        assertEquals(1, ledger.active(user).size)
        assertEquals(listOf(b2.id, b1.id).toSet(), ledger.batches(user, 10).map { it.id }.toSet())
        assertEquals(1, ledger.batches(user, 1).size)
    }

    @Test
    fun `another user can neither see nor undo a batch`() = runBlocking<Unit> {
        val user = newUser()
        val stranger = newUser()
        val acct = accounts.findOrCreate(account(user))
        val fund = instruments.findOrCreate(instrument(WealthFixtures.uniqueN()))
        val b = batch(user, events = 1)
        ledger.record(b, listOf(buy(user, acct, fund, b, day, "1", "10", "10.00")))
        assertNull(ledger.findBatch(stranger, b.id))
        assertFalse(ledger.undo(stranger, b.id, Instant.now()))
        assertTrue(ledger.active(stranger).isEmpty())
        assertTrue(ledger.batches(stranger, 10).isEmpty())
        assertEquals(ImportStatus.APPLIED, ledger.findBatch(user, b.id)?.status)
    }

    @Test
    fun `a batch whose rows belong to someone else is refused`() = runBlocking<Unit> {
        val user = newUser()
        val acct = accounts.findOrCreate(account(user))
        val fund = instruments.findOrCreate(instrument(WealthFixtures.uniqueN()))
        val b = batch(user, events = 1)
        val failure = runCatching { ledger.record(b, listOf(buy(UUID.randomUUID(), acct, fund, b, day, "1", "10", "10.00"))) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertNull(ledger.findBatch(user, b.id))
    }
}

class InMemoryWealthStoreTest : WealthStoreContract() {
    override val accounts = InMemoryInvestmentAccountRepository()
    override val instruments = InMemoryInstrumentRepository()
    override val ledger = InMemoryInvestmentLedger()
    override suspend fun newUser(): UUID = UUID.randomUUID()
}
