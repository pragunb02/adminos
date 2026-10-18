package dev.adminos.api.domain.wealth.prices

import dev.adminos.api.domain.wealth.AssetClass
import dev.adminos.api.domain.wealth.Instrument
import dev.adminos.api.domain.wealth.UnitPrice
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Where a price came from (D-79): official bulk files first; a statement's own valuation NAV as a fallback. */
enum class PriceSource {
    /** AMFI's daily NAV file (`NAVAll.txt`). */
    AMFI,
    /** The NAV printed on an imported statement (CAS valuation). */
    STATEMENT;

    val wire: String get() = name.lowercase()
}

/** One end-of-day price of an instrument on [date], in the instrument's currency. Never zero ([UnitPrice]). */
data class PriceObservation(val instrumentId: UUID, val date: LocalDate, val price: UnitPrice, val source: PriceSource, val fetchedAt: Instant)

interface PriceRepository {
    /** Inserts or replaces (instrument, date, source). Idempotent: re-running a day's fetch changes nothing. @return rows written */
    suspend fun upsert(prices: List<PriceObservation>): Int
    /**
     * For each of [instrumentIds], the latest price on or before [date] (holidays and weekends simply have no row,
     * WEALTH-RESEARCH §2.2). On the same date AMFI wins over a statement.
     */
    suspend fun latestOnOrBefore(instrumentIds: Collection<UUID>, date: LocalDate): Map<UUID, PriceObservation>
}

/** Chooses between prices on the same date: the official file over a statement. */
internal val PRICE_ORDER: Comparator<PriceObservation> = compareBy<PriceObservation>({ it.date }, { if (it.source == PriceSource.AMFI) 1 else 0 })

class InMemoryPriceRepository : PriceRepository {
    private val store = ConcurrentHashMap<Triple<UUID, LocalDate, PriceSource>, PriceObservation>()

    override suspend fun upsert(prices: List<PriceObservation>): Int {
        prices.forEach { store[Triple(it.instrumentId, it.date, it.source)] = it }
        return prices.size
    }

    override suspend fun latestOnOrBefore(instrumentIds: Collection<UUID>, date: LocalDate): Map<UUID, PriceObservation> {
        val ids = instrumentIds.toSet()
        return store.values.filter { it.instrumentId in ids && !it.date.isAfter(date) }
            .groupBy { it.instrumentId }
            .mapValues { (_, list) -> list.maxWith(PRICE_ORDER) }
    }
}

/**
 * A price as used for a valuation on [asOf]: the observation, how old it is, and whether that is older than the
 * instrument's limit. A stale price still counts (it is the best we have); it is labelled, never zeroed or dropped.
 */
data class PricedAt(val observation: PriceObservation, val asOf: LocalDate, val staleAfterDays: Long) {
    val ageDays: Long get() = ChronoUnit.DAYS.between(observation.date, asOf)
    val stale: Boolean get() = ageDays > staleAfterDays
}

/** How old a price may be before it is labelled stale (WEALTH-RESEARCH §2.2): a mutual fund NAV is daily, so 4 days. */
object Staleness {
    const val MUTUAL_FUND_DAYS = 4L
    const val LISTED_DAYS = 5L
    const val GOLD_DAYS = 35L
    const val PROPERTY_DAYS = 200L

    fun limitDays(instrument: Instrument): Long = when {
        instrument.amfiCode != null || instrument.mfPlan != null || instrument.isin?.startsWith("INF") == true -> MUTUAL_FUND_DAYS
        instrument.assetClass == AssetClass.REAL_ESTATE -> PROPERTY_DAYS
        instrument.assetClass == AssetClass.GOLD -> GOLD_DAYS
        else -> LISTED_DAYS
    }
}
