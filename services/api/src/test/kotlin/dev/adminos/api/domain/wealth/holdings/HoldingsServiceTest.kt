package dev.adminos.api.domain.wealth.holdings

import dev.adminos.api.domain.wealth.Quantity
import dev.adminos.api.domain.wealth.imports.CasFixtures
import dev.adminos.api.domain.wealth.imports.CasImportServiceTest
import dev.adminos.api.domain.wealth.prices.AmfiNavParserTest.Companion.file
import dev.adminos.api.domain.wealth.prices.AmfiNavParserTest.Companion.row
import dev.adminos.api.domain.wealth.returns.ReturnBasis
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AOS-820: the overview from the synthetic CAS (hand-computed numbers), blocked holdings kept apart. */
class HoldingsServiceTest {
    private val kit = CasImportServiceTest.Kit()
    private val user = UUID.randomUUID()
    private val service = HoldingsService(kit.ledger, kit.accounts, kit.instruments, kit.prices, kit.reconciliation)
    private fun same(expected: String, actual: BigDecimal) = assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected, was $actual")
    private suspend fun import() = kit.service.import(user, "%PDF-".toByteArray(), CasFixtures.PASSWORD)

    @Test
    fun `two reconciled holdings are valued at the statement NAV with gains and an absolute return under a year`() = runBlocking<Unit> {
        import()
        val o = service.overview(user, LocalDate.of(2026, 1, 2))
        same("43500", o.value); same("40000", o.invested); same("3500", o.gain); same("500", o.realised)
        assertEquals(LocalDate.of(2025, 12, 31), o.pricesAsOf); assertEquals(0, o.stale); assertEquals(0, o.valuedAtCost)
        val flexi = o.holdings.single { it.instrument.isin == "INFZZ9000010" }
        same("18000", flexi.value); same("15000", flexi.invested); same("3000", flexi.gain); same("0.2", flexi.gainRatio!!)
        assertEquals(Quantity.parse("150"), flexi.lots.units)
        assertEquals(ReturnBasis.ABSOLUTE, flexi.ret.basis); same("0.175", flexi.ret.shown!!)
        assertEquals(ReturnBasis.ABSOLUTE, o.ret.basis); assertEquals("8.9", percent(o.ret.shown))
        assertTrue(o.blocked.isEmpty())
        assertEquals(listOf("18000.00", "25500.00").sorted(), o.holdings.map { it.value.setScale(2).toPlainString() }.sorted())
    }

    @Test
    fun `after a year the return is XIRR and an old price is labelled stale`() = runBlocking<Unit> {
        import()
        val o = service.overview(user, LocalDate.of(2026, 2, 1))
        assertEquals(2, o.stale)
        val flexi = o.holdings.single { it.instrument.isin == "INFZZ9000010" }
        assertEquals(ReturnBasis.ANNUALISED, flexi.ret.basis)
        assertTrue(flexi.price!!.stale)
    }

    @Test
    fun `a fresh AMFI NAV is used, and a holding without any price is valued at cost`() = runBlocking<Unit> {
        kit.next = { CasFixtures.withFlexi { it.copy(nav = null) } }
        import()
        val atCost = service.overview(user, LocalDate.of(2026, 1, 2))
        val flexi = atCost.holdings.single { it.instrument.isin == "INFZZ9000010" }
        assertTrue(flexi.valuedAtCost); same("15000", flexi.value); assertEquals(1, atCost.valuedAtCost)
        kit.navFile = { file(row("900001", "INFZZ9000010", "-", "130.00", date = "01-Jan-2026")) }
        kit.prices.refreshFromAmfi()
        same("19500", service.overview(user, LocalDate.of(2026, 1, 2)).holdings.single { it.instrument.isin == "INFZZ9000010" }.value)
    }

    @Test
    fun `a blocked holding is listed for review and kept out of every total`() = runBlocking<Unit> {
        kit.next = { CasFixtures.withFlexi { it.copy(closingUnits = Quantity.parse("151")) } }
        import()
        val o = service.overview(user, LocalDate.of(2026, 1, 2))
        assertEquals(listOf("INFZZ9000028"), o.holdings.map { it.instrument.isin })
        same("25500", o.value)
        assertEquals("••••4/56", o.blocked.single().folioMasked)
        assertTrue(o.blocked.single().status.reason.orEmpty().startsWith("Units don't add up"))
    }

    @Test
    fun `nothing imported gives an empty overview and someone else sees nothing`() = runBlocking<Unit> {
        import()
        val empty = service.overview(UUID.randomUUID(), LocalDate.of(2026, 1, 2))
        assertTrue(empty.groups.isEmpty()); same("0", empty.value); assertEquals(ReturnBasis.NONE, empty.ret.basis)
        assertEquals(null, empty.pricesAsOf)
    }

    @Test
    fun `the response explains how each number was made and shows the folio masked`() = runBlocking<Unit> {
        import()
        val dto = service.overview(user, LocalDate.of(2026, 1, 2)).dto()
        assertEquals("43500.00", dto.value); assertEquals("8.8", dto.gainPercent); assertEquals("absolute", dto.ret.basis)
        val h = dto.groups.flatMap { it.holdings }.single { it.isin == "INFZZ9000010" }
        assertEquals("••••4/56", h.folio); assertEquals("17.5", h.ret.percent); assertEquals("20.0", h.gainPercent)
        assertEquals("fifo", h.how.costMethod); assertEquals(3, h.how.lots); assertEquals("statement", h.how.priceSource)
        assertTrue(h.how.steps.any { it.startsWith("Value = units × price = 18000.00") })
        assertTrue(h.how.steps.any { "absolute return" in it })
        assertTrue(dto.groups.flatMap { it.holdings }.none { "90001234" in it.toString() })
    }
}
