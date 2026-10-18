package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.wealth.UnitPrice
import dev.adminos.api.domain.wealth.prices.PRICE_ORDER
import dev.adminos.api.domain.wealth.prices.PriceObservation
import dev.adminos.api.domain.wealth.prices.PriceRepository
import dev.adminos.api.domain.wealth.prices.PriceSource
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.InstrumentPricesTable
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.LocalDate
import java.util.UUID

/** Postgres prices (AOS-810): upsert per (instrument, date, source); "latest on or before" in one query. */
class ExposedPriceRepository : PriceRepository {
    private val t = InstrumentPricesTable

    override suspend fun upsert(prices: List<PriceObservation>): Int {
        if (prices.isEmpty()) return 0
        return dbQuery {
            t.batchUpsert(prices, t.instrumentId, t.priceDate, t.priceSource, shouldReturnGeneratedValues = false) { p ->
                this[t.instrumentId] = p.instrumentId; this[t.priceDate] = p.date; this[t.priceSource] = p.source.wire
                this[t.price] = p.price.value; this[t.fetchedAt] = p.fetchedAt
            }
            prices.size
        }
    }

    override suspend fun latestOnOrBefore(instrumentIds: Collection<UUID>, date: LocalDate): Map<UUID, PriceObservation> {
        if (instrumentIds.isEmpty()) return emptyMap()
        val text = TextColumnType()
        val ids = instrumentIds.toSet().joinToString(",") // UUIDs only (typed), passed as one bound parameter
        return dbQuery {
            TransactionManager.current().exec(
                """
                SELECT DISTINCT ON (instrument_id) instrument_id, price_date, source, price, fetched_at
                FROM instrument_prices
                WHERE instrument_id = ANY (string_to_array(?, ',')::uuid[]) AND price_date <= ?::date
                ORDER BY instrument_id, price_date DESC, (source = 'amfi') DESC
                """.trimIndent(),
                listOf(text to ids, text to date.toString())
            ) { rs ->
                val out = mutableListOf<PriceObservation>()
                while (rs.next()) {
                    out += PriceObservation(
                        instrumentId = rs.getObject("instrument_id", UUID::class.java),
                        date = rs.getObject("price_date", LocalDate::class.java),
                        price = UnitPrice.of(rs.getBigDecimal("price")),
                        source = PriceSource.valueOf(rs.getString("source").uppercase()),
                        fetchedAt = rs.getTimestamp("fetched_at").toInstant()
                    )
                }
                out
            }.orEmpty().groupBy { it.instrumentId }.mapValues { (_, list) -> list.maxWith(PRICE_ORDER) }
        }
    }
}
