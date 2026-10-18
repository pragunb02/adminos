package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.wealth.InvestmentTxnType
import dev.adminos.api.domain.wealth.WealthFixtures
import dev.adminos.api.domain.wealth.WealthFixtures.account
import dev.adminos.api.domain.wealth.WealthFixtures.batch
import dev.adminos.api.domain.wealth.WealthFixtures.buy
import dev.adminos.api.domain.wealth.WealthFixtures.instrument
import dev.adminos.api.domain.wealth.WealthStoreContract
import dev.adminos.api.domain.wealth.FxRate
import dev.adminos.api.domain.wealth.FxRateType
import dev.adminos.api.domain.wealth.INR
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-802 on Postgres: the store contract, plus what only the database enforces. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedWealthStoreIntegrationTest : WealthStoreContract() {
    override val accounts = ExposedInvestmentAccountRepository()
    override val instruments = ExposedInstrumentRepository()
    override val ledger = ExposedInvestmentLedger()
    override suspend fun newUser(): UUID = PostgresTestDatabase.newUser()

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private fun sql(query: String, vararg params: Any): List<Map<String, Any?>> = PostgresTestDatabase.dataSource.connection.use { c ->
        c.prepareStatement(query).use { st ->
            params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
            if (!st.execute()) return emptyList()
            st.resultSet.use { rs ->
                val out = mutableListOf<Map<String, Any?>>()
                while (rs.next()) out += (1..rs.metaData.columnCount).associate { rs.metaData.getColumnLabel(it) to rs.getObject(it) }
                out
            }
        }
    }

    @Test
    fun `the database holds only ciphertext, a blind index and the last 4 of a folio`() = runBlocking<Unit> {
        val user = newUser()
        val acct = accounts.findOrCreate(account(user, "98765432"))
        val row = sql("SELECT * FROM investment_accounts WHERE id = ?", acct.id).single()
        assertFalse(row.values.any { it?.toString()?.contains("98765432") == true }, "the folio never appears in clear")
        assertTrue(row["identifier_enc"].toString().startsWith("v2:"))
        assertEquals("5432", row["identifier_last4"])
    }

    @Test
    fun `a ledger row can't be changed in place`() = runBlocking<Unit> {
        val user = newUser()
        val acct = accounts.findOrCreate(account(user))
        val fund = instruments.findOrCreate(instrument(WealthFixtures.uniqueN()))
        val b = batch(user, events = 1)
        val row = buy(user, acct, fund, b, LocalDate.of(2025, 5, 1), "10", "10", "100.00")
        ledger.record(b, listOf(row))
        val failure = runCatching { sql("UPDATE investment_transactions SET units = 1000 WHERE id = ?", row.id) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("append-only") == true, "update refused: ${failure?.message}")
        assertEquals(0, BigDecimal("10").compareTo(sql("SELECT units FROM investment_transactions WHERE id = ?", row.id).single()["units"] as BigDecimal))
    }

    @Test
    fun `two imports of the same file at once add each row once`() = runBlocking<Unit> {
        val user = newUser()
        val acct = accounts.findOrCreate(account(user))
        val fund = instruments.findOrCreate(instrument(WealthFixtures.uniqueN()))
        val results = (1..4).map {
            async {
                val b = batch(user, events = 5)
                ledger.record(b, (1..5).map { i -> buy(user, acct, fund, b, LocalDate.of(2025, i, 1), "$i", "10", "${i * 10}.00") })
            }
        }.awaitAll()
        assertEquals(5, results.sumOf { it.added })
        assertEquals(15, results.sumOf { it.duplicates })
        assertEquals(5, ledger.active(user).size)
    }

    @Test
    fun `a foreign row keeps its currency, rate type and INR value`() = runBlocking<Unit> {
        val user = newUser()
        val acct = accounts.findOrCreate(account(user))
        val usd = Currency.getInstance("USD")
        val stock = instruments.findOrCreate(dev.adminos.api.domain.wealth.Instrument(name = "Example Corp", assetClass = dev.adminos.api.domain.wealth.AssetClass.EQUITY_FOREIGN,
            ticker = "EXF${WealthFixtures.uniqueN()}", exchange = "NASDAQ", currency = usd))
        val b = batch(user, events = 1)
        val day = LocalDate.of(2025, 6, 2)
        val row = buy(user, acct, stock, b, day, "2.5", "120.1234", "300.31").copy(currency = usd,
            fx = FxRate(usd, INR, BigDecimal("83.4567"), FxRateType.SBI_TTBR, day), inrAmount = BigDecimal("25062.88"), type = InvestmentTxnType.BUY)
        ledger.record(b, listOf(row))
        val back = ledger.active(user).single()
        assertEquals(usd, back.currency)
        assertEquals(FxRateType.SBI_TTBR, back.fx?.type)
        assertEquals(0, BigDecimal("83.4567").compareTo(back.fx?.rate))
        assertEquals(0, BigDecimal("25062.88").compareTo(back.amountInr))
    }
}
