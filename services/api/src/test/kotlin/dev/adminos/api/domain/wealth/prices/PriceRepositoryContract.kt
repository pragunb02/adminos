package dev.adminos.api.domain.wealth.prices

import dev.adminos.api.domain.wealth.InstrumentRepository
import dev.adminos.api.domain.wealth.InMemoryInstrumentRepository
import dev.adminos.api.domain.wealth.UnitPrice
import dev.adminos.api.domain.wealth.WealthFixtures
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AOS-810: price storage, run against memory ([InMemoryPriceRepositoryTest]) and Postgres (`ExposedPriceRepositoryIntegrationTest`). */
abstract class PriceRepositoryContract {
    abstract val prices: PriceRepository
    abstract val instruments: InstrumentRepository

    private val at = Instant.parse("2026-10-17T18:00:00Z")
    private fun obs(id: UUID, date: String, price: String, source: PriceSource = PriceSource.AMFI) =
        PriceObservation(id, LocalDate.parse(date), UnitPrice.parse(price), source, at)

    @Test
    fun `the latest price on or before a date wins, per instrument`() = runBlocking<Unit> {
        val a = instruments.findOrCreate(WealthFixtures.instrument(WealthFixtures.uniqueN())).id
        val b = instruments.findOrCreate(WealthFixtures.instrument(WealthFixtures.uniqueN())).id
        assertEquals(3, prices.upsert(listOf(obs(a, "2026-10-14", "10"), obs(a, "2026-10-16", "11"), obs(b, "2026-10-15", "20"))))
        val on15 = prices.latestOnOrBefore(listOf(a, b), LocalDate.parse("2026-10-15"))
        assertEquals(UnitPrice.parse("10"), on15.getValue(a).price)
        assertEquals(UnitPrice.parse("20"), on15.getValue(b).price)
        assertEquals(UnitPrice.parse("11"), prices.latestOnOrBefore(listOf(a), LocalDate.parse("2026-12-31")).getValue(a).price)
        assertTrue(prices.latestOnOrBefore(listOf(a), LocalDate.parse("2026-10-13")).isEmpty())
        assertTrue(prices.latestOnOrBefore(emptyList(), LocalDate.parse("2026-10-16")).isEmpty())
    }

    @Test
    fun `re-running a day replaces its price, and AMFI wins over a statement on the same date`() = runBlocking<Unit> {
        val a = instruments.findOrCreate(WealthFixtures.instrument(WealthFixtures.uniqueN())).id
        prices.upsert(listOf(obs(a, "2026-10-16", "11.5", PriceSource.STATEMENT)))
        assertEquals(PriceSource.STATEMENT, prices.latestOnOrBefore(listOf(a), LocalDate.parse("2026-10-16")).getValue(a).source)
        prices.upsert(listOf(obs(a, "2026-10-16", "11"), obs(a, "2026-10-16", "11.25")))
        val latest = prices.latestOnOrBefore(listOf(a), LocalDate.parse("2026-10-16")).getValue(a)
        assertEquals(PriceSource.AMFI, latest.source)
        assertEquals(UnitPrice.parse("11.25"), latest.price)
        assertEquals(0, prices.upsert(emptyList()))
    }
}

class InMemoryPriceRepositoryTest : PriceRepositoryContract() {
    override val prices = InMemoryPriceRepository()
    override val instruments = InMemoryInstrumentRepository()
}
