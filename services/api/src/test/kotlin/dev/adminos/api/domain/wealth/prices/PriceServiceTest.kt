package dev.adminos.api.domain.wealth.prices

import dev.adminos.api.domain.wealth.AssetClass
import dev.adminos.api.domain.wealth.Instrument
import dev.adminos.api.domain.wealth.UnitPrice
import dev.adminos.api.domain.wealth.WealthAuditActions
import dev.adminos.api.domain.wealth.imports.CasFixtures
import dev.adminos.api.domain.wealth.imports.CasImportServiceTest
import dev.adminos.api.domain.wealth.prices.AmfiNavParserTest.Companion.file
import dev.adminos.api.domain.wealth.prices.AmfiNavParserTest.Companion.row
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-810: AMFI NAVs matched to known instruments, statement NAVs as a fallback, staleness labelled. */
class PriceServiceTest {
    private val kit = CasImportServiceTest.Kit()
    private val user = UUID.randomUUID()
    private val oct16 = LocalDate.of(2026, 10, 16)

    private suspend fun growth() = kit.instruments.findOrCreate(Instrument(name = "Example Fund - Direct - IDCW Payout", assetClass = AssetClass.EQUITY_IN,
        isin = "INFZZ9100018", amfiCode = "910001"))
    private suspend fun reinvest() = kit.instruments.findOrCreate(Instrument(name = "Example Fund - Direct - IDCW Reinvest", assetClass = AssetClass.EQUITY_IN,
        isin = "INFZZ9100026", amfiCode = "910001"))

    @Test
    fun `NAVs are matched by ISIN, then AMFI code, and only known schemes are stored`() = runBlocking<Unit> {
        val payout = growth(); val twin = reinvest()
        val codeOnly = kit.instruments.findOrCreate(Instrument(name = "Code only", assetClass = AssetClass.DEBT, amfiCode = "920002"))
        val unpriced = kit.instruments.findOrCreate(Instrument(name = "Not in the file", assetClass = AssetClass.DEBT, isin = "INFZZ9300014"))
        kit.instruments.findOrCreate(Instrument(name = "A US stock", assetClass = AssetClass.EQUITY_FOREIGN, ticker = "EXMPL", exchange = "NYSE", currency = Currency.getInstance("USD")))
        kit.navFile = { file(
            row("910001", "INFZZ9100018", "INFZZ9100026", "50.5000"),
            row("920002", "-", "-", "1050.25"),
            row("999999", "INFZZ9999990", "-", "10.00"),
        ) }
        val r = kit.prices.refreshFromAmfi()
        assertEquals(3, r.rowsInFile); assertEquals(4, r.instruments, "the US stock isn't a mutual fund"); assertEquals(3, r.stored); assertEquals(1, r.unmatched)
        val at = kit.prices.priceAt(listOf(payout, twin, codeOnly, unpriced), oct16)
        assertEquals(UnitPrice.parse("50.5"), at.getValue(payout.id).observation.price)
        assertEquals(UnitPrice.parse("50.5"), at.getValue(twin.id).observation.price, "the reinvestment twin shares the scheme's NAV")
        assertEquals(UnitPrice.parse("1050.25"), at.getValue(codeOnly.id).observation.price)
        assertNull(at[unpriced.id], "no price at all: valued at cost by the caller, never zero")
        assertEquals(PriceSource.AMFI, at.getValue(payout.id).observation.source)
    }

    @Test
    fun `a re-run of the same day's file changes nothing`() = runBlocking<Unit> {
        val fund = growth()
        kit.navFile = { file(row("910001", "INFZZ9100018", "-", "50.5000")) }
        kit.prices.refreshFromAmfi(); kit.prices.refreshFromAmfi()
        assertEquals(UnitPrice.parse("50.5"), kit.prices.priceAt(listOf(fund), oct16).getValue(fund.id).observation.price)
    }

    @Test
    fun `the price used is the latest on or before the date, and older than 4 days is stale`() = runBlocking<Unit> {
        val fund = growth()
        kit.navFile = { file(row("910001", "INFZZ9100018", "-", "48.00", date = "09-Oct-2026")) }
        kit.prices.refreshFromAmfi()
        kit.navFile = { file(row("910001", "INFZZ9100018", "-", "50.00", date = "16-Oct-2026")) }
        kit.prices.refreshFromAmfi()
        assertEquals(UnitPrice.parse("48"), kit.prices.priceAt(listOf(fund), LocalDate.of(2026, 10, 12)).getValue(fund.id).observation.price)
        val fresh = kit.prices.priceAt(listOf(fund), LocalDate.of(2026, 10, 20)).getValue(fund.id)
        assertEquals(4, fresh.ageDays); assertFalse(fresh.stale)
        val old = kit.prices.priceAt(listOf(fund), LocalDate.of(2026, 10, 21)).getValue(fund.id)
        assertEquals(5, old.ageDays); assertTrue(old.stale, "labelled stale, still the price used")
        assertTrue(kit.prices.priceAt(listOf(fund), LocalDate.of(2026, 10, 1)).isEmpty())
    }

    @Test
    fun `an import keeps the statement's NAV, and AMFI wins on the same date`() = runBlocking<Unit> {
        kit.service.import(user, "%PDF-".toByteArray(), CasFixtures.PASSWORD)
        val flexi = kit.instruments.mutualFunds().first { it.isin == "INFZZ9000010" }
        val fromStatement = kit.prices.priceAt(listOf(flexi), LocalDate.of(2026, 1, 2)).getValue(flexi.id)
        assertEquals(PriceSource.STATEMENT, fromStatement.observation.source)
        assertEquals(UnitPrice.parse("120"), fromStatement.observation.price)
        assertEquals(LocalDate.of(2025, 12, 31), fromStatement.observation.date)

        kit.navFile = { file(row("900001", "INFZZ9000010", "-", "121.00", date = "31-Dec-2025")) }
        kit.prices.refreshFromAmfi()
        val both = kit.prices.priceAt(listOf(flexi), LocalDate.of(2026, 1, 2)).getValue(flexi.id)
        assertEquals(PriceSource.AMFI, both.observation.source); assertEquals(UnitPrice.parse("121"), both.observation.price)
    }

    @Test
    fun `each fetch is audited with counts, and the job reports them`() = runBlocking<Unit> {
        growth()
        kit.navFile = { file(row("910001", "INFZZ9100018", "-", "50.5000"), row("1", "-", "-", "0")) }
        val result = AmfiNavJob(kit.prices, ZoneId.of("Asia/Kolkata")).run()
        assertEquals(mapOf("rows" to 1, "skipped" to 1, "instruments" to 1, "stored" to 1, "unmatched" to 0), result.counts)
        val r = kit.prices.refreshFromAmfi()
        val audit = kit.auditLog.findByEntity("price_fetch", r.runId).single()
        assertEquals(WealthAuditActions.PRICE_FETCH, audit.action)
        assertEquals("amfi", audit.metadata?.get("source")?.jsonPrimitive?.content)
        assertEquals("1", audit.metadata?.get("stored")?.jsonPrimitive?.content)
    }

    @Test
    fun `statement NAVs without an instrument or a NAV are not prices`() = runBlocking<Unit> {
        kit.next = { CasFixtures.withFlexi { it.copy(nav = null) } }
        kit.service.import(user, "%PDF-".toByteArray(), CasFixtures.PASSWORD)
        val flexi = kit.instruments.mutualFunds().first { it.isin == "INFZZ9000010" }
        assertTrue(kit.prices.priceAt(listOf(flexi), LocalDate.of(2026, 1, 2)).isEmpty())
        assertEquals(Instant.parse("2026-10-18T06:00:00Z"), kit.priceStore.latestOnOrBefore(kit.instruments.mutualFunds().map { it.id }, LocalDate.of(2026, 1, 2)).values.single().fetchedAt)
    }
}
