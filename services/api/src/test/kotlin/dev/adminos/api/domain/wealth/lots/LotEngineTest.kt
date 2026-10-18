package dev.adminos.api.domain.wealth.lots

import dev.adminos.api.domain.wealth.FxRate
import dev.adminos.api.domain.wealth.FxRateType
import dev.adminos.api.domain.wealth.INR
import dev.adminos.api.domain.wealth.InMemoryInvestmentLedger
import dev.adminos.api.domain.wealth.InvestmentTransaction
import dev.adminos.api.domain.wealth.InvestmentTxnType
import dev.adminos.api.domain.wealth.InvestmentTxnType.*
import dev.adminos.api.domain.wealth.Quantity
import dev.adminos.api.domain.wealth.UnitPrice
import dev.adminos.api.domain.wealth.WealthFixtures
import dev.adminos.api.domain.wealth.imports.CasImportServiceTest
import dev.adminos.api.domain.wealth.imports.CasFixtures
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AOS-811: FIFO lots and gains against hand-computed golden cases (synthetic amounts). */
class LotEngineTest {
    private val user = UUID.randomUUID()
    private val account = UUID.randomUUID()
    private val fundA = UUID.randomUUID()
    private val fundB = UUID.randomUUID()
    private var seq = 0

    private fun row(type: InvestmentTxnType, date: String, units: String?, amount: String?, instrument: UUID = fundA,
                    currency: Currency = INR, fx: FxRate? = null, inr: String? = null) = InvestmentTransaction(
        userId = user, accountId = account, instrumentId = instrument, batchId = null, type = type, tradeDate = LocalDate.parse(date),
        units = units?.let(Quantity::parse), unitPrice = null, amount = amount?.let(::BigDecimal), currency = currency, fx = fx,
        inrAmount = inr?.let(::BigDecimal), sourceRef = WealthFixtures.ref(seq++))

    private fun lots(vararg rows: InvestmentTransaction, instrument: UUID = fundA) = LotEngine.run(rows.toList()).getValue(instrument)
    private fun money(s: String) = BigDecimal(s)
    private fun same(expected: String, actual: BigDecimal?) = assertEquals(0, money(expected).compareTo(actual), "expected $expected, was $actual")

    @Test
    fun `the golden statement gives three lots with stamp duty in their cost and one FIFO sale`() = runBlocking<Unit> {
        val kit = CasImportServiceTest.Kit()
        kit.service.import(user, "%PDF-".toByteArray(), CasFixtures.PASSWORD)
        val flexi = LotService(kit.ledger).holdings(user).first { h -> h.open.size == 3 }
        assertEquals(listOf("2025-01-10", "2025-02-10", "2025-03-10"), flexi.open.map { it.acquiredOn.toString() })
        assertEquals(listOf(Quantity.parse("50"), Quantity.parse("48"), Quantity.parse("52")), flexi.open.map { it.units })
        flexi.open.forEach { same("5000.00", it.cost) }  // 9,999.50 + 0.50 halved; 4,999.75 + 0.25; 4,999.75 + 0.25
        same("15000", flexi.cost)
        val sale = flexi.disposals.single()
        assertEquals(DisposalKind.SALE, sale.kind); assertEquals(Quantity.parse("50"), sale.units)
        same("5000", sale.cost); same("5500", sale.proceeds); same("500", sale.gain); assertEquals(156, sale.holdingDays)
        same("500", flexi.realised)
        same("3000", flexi.unrealisedAt(UnitPrice.parse("120")))
        same("18000", flexi.valueAt(UnitPrice.parse("120")))
    }

    @Test
    fun `a sale across two lots shares the proceeds by units and gains per lot`() {
        val h = lots(row(BUY, "2025-01-01", "10", "1000.00"), row(BUY, "2025-02-01", "10", "2000.00"), row(SELL, "2025-03-01", "-15", "3000.00"))
        assertEquals(2, h.disposals.size)
        same("1000", h.disposals[0].cost); same("2000", h.disposals[0].proceeds); same("1000", h.disposals[0].gain)
        same("1000", h.disposals[1].cost); same("1000", h.disposals[1].proceeds); same("0", h.disposals[1].gain)
        assertEquals(Quantity.parse("5"), h.units); same("1000", h.cost); same("1000", h.realised)
    }

    @Test
    fun `a third of a lot keeps the cost exact, with nothing lost to rounding`() {
        val h = lots(row(BUY, "2025-01-01", "3", "100.00"), row(SELL, "2025-06-01", "-1", "40.00"))
        same("33.3333333333", h.disposals.single().cost)
        same("6.6666666667", h.realised)
        same("100", h.cost + h.disposals.single().cost)
    }

    @Test
    fun `bonus units cost nothing and are sold after the original lot`() {
        val h = lots(row(BUY, "2025-01-01", "10", "1000.00"), row(BONUS, "2025-02-01", "10", null), row(SELL, "2025-03-01", "-15", "2250.00"))
        same("1000", h.disposals[0].cost); same("0", h.disposals[1].cost)
        assertEquals(LocalDate.parse("2025-02-01"), h.disposals[1].acquiredOn)
        same("0", h.cost); assertEquals(Quantity.parse("5"), h.units)
    }

    @Test
    fun `a split changes units, never cost or the acquisition date`() {
        val h = lots(row(BUY, "2025-01-01", "10", "1000.00"), row(BUY, "2025-02-01", "5", "600.00"), row(SPLIT, "2025-03-01", "15", null))
        assertEquals(listOf(Quantity.parse("20"), Quantity.parse("10")), h.open.map { it.units })
        same("1600", h.cost); assertEquals(LocalDate.parse("2025-01-01"), h.open.first().acquiredOn)
        assertEquals(Quantity.parse("10"), lots(row(BUY, "2025-01-01", "3", "100.00"), row(SPLIT, "2025-02-01", "7", null)).units, "uneven ratios stay exact")
    }

    @Test
    fun `a reversed SIP removes the newest lot with no gain`() {
        val h = lots(row(SIP_BUY, "2025-01-10", "10", "1000.00"), row(SIP_BUY, "2025-02-10", "9", "1000.00"), row(REVERSAL, "2025-02-10", "-9", "1000.00"))
        assertEquals(listOf(LocalDate.parse("2025-01-10")), h.open.map { it.acquiredOn })
        assertEquals(DisposalKind.REVERSAL, h.disposals.single().kind); same("0", h.realised)
        assertEquals(Quantity.parse("2"), lots(row(REVERSAL, "2025-03-01", "2", "200.00")).units, "a reversal that returns units reopens a lot")
    }

    @Test
    fun `a scheme merger carries cost and the original dates into the new scheme`() {
        val all = LotEngine.run(listOf(
            row(BUY, "2024-01-01", "10", "1000.00"), row(BUY, "2024-06-01", "10", "1500.00"),
            row(MERGER_IN, "2025-01-01", "40", "3000.00", instrument = fundB), row(MERGER_OUT, "2025-01-01", "-20", "3000.00"),
        ))
        val old = all.getValue(fundA); val new = all.getValue(fundB)
        assertEquals(Quantity.ZERO, old.units); same("0", old.realised)
        assertEquals(listOf(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-06-01")), new.open.map { it.acquiredOn })
        assertEquals(listOf(Quantity.parse("20"), Quantity.parse("20")), new.open.map { it.units })
        same("2500", new.cost)
        val unmatched = lots(row(MERGER_IN, "2025-01-01", "5", "500.00"))
        assertTrue(LotIssue.MERGER_UNMATCHED in unmatched.issues); assertTrue(unmatched.open.single().costEstimated)
    }

    @Test
    fun `gifts out leave without a gain and gifts in without a cost are flagged`() {
        val h = lots(row(BUY, "2025-01-01", "10", "1000.00"), row(TRANSFER_OUT, "2025-02-01", "-4", null))
        assertEquals(DisposalKind.TRANSFER_OUT, h.disposals.single().kind); same("0", h.realised); same("600", h.cost)
        val gift = lots(row(TRANSFER_IN, "2025-01-01", "5", null))
        assertTrue(LotIssue.COST_UNKNOWN in gift.issues); same("0", gift.cost)
    }

    @Test
    fun `selling more than was bought is flagged, never a negative lot`() {
        val h = lots(row(BUY, "2025-01-01", "1", "100.00"), row(SELL, "2025-02-01", "-3", "300.00"))
        assertTrue(LotIssue.OVERSOLD in h.issues); assertEquals(Quantity.ZERO, h.units)
        assertTrue(LotIssue.OVERSOLD in lots(row(BUY, "2025-01-01", "1", "100.00"), row(SPLIT, "2025-02-01", "-1", null)).issues)
    }

    @Test
    fun `dividends, taxes and stray stamp duty are tracked, not costs`() {
        val h = lots(row(BUY, "2025-01-01", "10", "1000.00"), row(DIVIDEND_PAYOUT, "2025-03-01", null, "50.00"), row(TDS, "2025-03-01", null, "5.00"),
            row(STT, "2025-04-01", null, "0.10"), row(STAMP_DUTY, "2025-05-01", null, "0.05"), row(MISC, "2025-05-02", null, null))
        same("50", h.dividends); same("5.10", h.taxes); same("0.05", h.unallocatedFees); same("1000", h.cost)
    }

    @Test
    fun `two purchases on one day each get their own stamp duty`() {
        val h = lots(row(BUY, "2025-01-01", "10", "999.95"), row(BUY, "2025-01-01", "20", "1999.90"),
            row(STAMP_DUTY, "2025-01-01", null, "0.05"), row(STAMP_DUTY, "2025-01-01", null, "0.10"))
        assertEquals(listOf("1000.00", "2000.00"), h.open.map { it.cost.setScale(2).toPlainString() })
    }

    @Test
    fun `a foreign sale has its gain in dollars and in rupees, each side at its own rate`() {
        val usd = Currency.getInstance("USD")
        val buyFx = FxRate(usd, INR, BigDecimal("83"), FxRateType.SBI_TTBR, LocalDate.parse("2025-01-02"))
        val sellFx = FxRate(usd, INR, BigDecimal("85"), FxRateType.SBI_TTBR, LocalDate.parse("2025-09-01"))
        val h = lots(row(BUY, "2025-01-02", "2", "200.00", currency = usd, fx = buyFx, inr = "16600.00"),
            row(SELL, "2025-09-01", "-1", "150.00", currency = usd, fx = sellFx, inr = "12750.00"))
        same("50", h.realised); same("4450", h.realisedInr); same("8300", h.costInr)
        val split = GainSplit.of(BigDecimal("100"), BigDecimal("150"), Quantity.parse("1"), BigDecimal("83"), BigDecimal("85"))
        same("4150", split.priceEffect); same("300", split.fxEffect); same("4450", split.total)
    }

    @Test
    fun `random buys and sells always conserve units and cost`() {
        val rnd = Random(805)
        repeat(200) { trial ->
            val rows = mutableListOf<InvestmentTransaction>()
            var held = BigDecimal.ZERO; var bought = BigDecimal.ZERO
            var day = LocalDate.parse("2024-01-01")
            repeat(rnd.nextInt(1, 30)) {
                day = day.plusDays(rnd.nextLong(0, 20))
                if (held.signum() == 0 || rnd.nextBoolean()) {
                    val u = BigDecimal(rnd.nextInt(1, 100_000)).movePointLeft(3)
                    val a = BigDecimal(rnd.nextInt(1, 10_000_000)).movePointLeft(2)
                    rows += row(SIP_BUY, day.toString(), u.toPlainString(), a.toPlainString()); held += u; bought += a
                } else {
                    val u = held.multiply(BigDecimal(rnd.nextInt(1, 101))).divide(BigDecimal(100)).setScale(3, java.math.RoundingMode.DOWN).max(BigDecimal("0.001")).min(held)
                    rows += row(SELL, day.toString(), u.negate().toPlainString(), BigDecimal(rnd.nextInt(0, 10_000_000)).movePointLeft(2).toPlainString()); held -= u
                }
            }
            val h = LotEngine.run(rows).getValue(fundA)
            assertEquals(0, held.compareTo(h.units.value), "trial $trial: units")
            assertEquals(0, bought.compareTo(h.cost + h.disposals.fold(BigDecimal.ZERO) { s, d -> s + d.cost }), "trial $trial: cost is conserved")
            assertTrue(h.issues.isEmpty(), "trial $trial: ${h.issues}")
        }
    }

    @Test
    fun `lots are computed per account`() {
        val other = row(BUY, "2025-01-01", "1", "1.00").copy(accountId = UUID.randomUUID())
        kotlin.test.assertFailsWith<IllegalArgumentException> { LotEngine.run(listOf(row(BUY, "2025-01-01", "1", "1.00"), other)) }
        runBlocking { assertTrue(LotService(InMemoryInvestmentLedger()).holdings(user).isEmpty()) }
    }
}
